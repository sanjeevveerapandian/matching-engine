package com.sanjeev.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Takes incoming orders and trades them against the book.
 *
 * <p>Rules (never break these):
 * <ul>
 *   <li>Best price first, then oldest order first (price-time priority).</li>
 *   <li>The trade happens at the RESTING order's price, not the incoming one's.</li>
 *   <li>GTC limit orders rest what is left; IOC and market orders cancel what is left;
 *       FOK orders trade completely or not at all.</li>
 * </ul>
 *
 * <p>Single-threaded on purpose: one order is fully processed before the next starts.
 */
public final class MatchingEngine {

    private final OrderBook book = new OrderBook();
    private long nextTradeId = 1;
    /** Highest sequence number seen so far; modify() hands out the next one up. */
    private long lastSequence = 0;

    /**
     * Match a LIMIT order against the opposite side, then act on its time in force:
     * GTC rests the leftover, IOC cancels it, FOK refuses to trade unless it can fill
     * the whole quantity (checked BEFORE any trade is made).
     *
     * @return the trades, in the order they happened (empty if nothing matched)
     * @throws IllegalArgumentException if null, not a limit order, or the id is already resting
     */
    public List<Trade> submitLimit(Order incoming) {
        validate(incoming, OrderType.LIMIT);
        requireQueueOrder(incoming);
        lastSequence = Math.max(lastSequence, incoming.getSequence());

        if (incoming.getTimeInForce() == TimeInForce.FOK
                && availableToTrade(incoming) < incoming.getRemainingQuantity()) {
            return List.of(); // not enough on the book: do nothing at all
        }

        List<Trade> trades = match(incoming);
        if (!incoming.isFullyFilled() && incoming.getTimeInForce() == TimeInForce.GTC) {
            book.addResting(incoming);
        }
        return trades;
    }

    /**
     * Match a MARKET order against the best available prices until it is filled or the
     * opposite side runs out. It never rests: anything unfilled is simply cancelled
     * (check {@code incoming.getRemainingQuantity()} to see how much).
     *
     * @return the trades, in the order they happened (empty if the book side was empty)
     * @throws IllegalArgumentException if null, not a market order, or the id is already resting
     */
    public List<Trade> submitMarket(Order incoming) {
        validate(incoming, OrderType.MARKET);
        lastSequence = Math.max(lastSequence, incoming.getSequence());

        return match(incoming); // no resting step: that is what makes it a market order
    }

    /**
     * Cancel a resting order.
     *
     * @return true if cancelled; false if the id is unknown or the order is already filled
     */
    public boolean cancel(long orderId) {
        return book.cancel(orderId);
    }

    /**
     * Change a resting order.
     *
     * <ul>
     *   <li>Same price and a SMALLER quantity: the order shrinks in place and KEEPS its
     *       place in the queue (nobody behind it is hurt).</li>
     *   <li>Same price and same quantity: nothing happens.</li>
     *   <li>Price changed, or quantity INCREASED: the order LOSES its place. It is cancelled
     *       and re-submitted as a new order with a new sequence number, so it can trade
     *       immediately if it now crosses the book.</li>
     * </ul>
     *
     * @param newQuantity the new quantity still wanted (the unfilled part), must be positive
     * @return trades caused by a re-submission (empty for an in-place change)
     * @throws IllegalArgumentException if the order is not resting, or price/quantity invalid;
     *         an invalid request leaves the order exactly as it was
     */
    public List<Trade> modify(long orderId, long newPrice, long newQuantity) {
        Order order = book.restingOrder(orderId);
        if (order == null) {
            throw new IllegalArgumentException("order " + orderId + " is not resting on the book");
        }
        if (newPrice <= 0) {
            throw new IllegalArgumentException("price must be positive, got " + newPrice);
        }
        if (newQuantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + newQuantity);
        }

        boolean samePrice = newPrice == order.getPriceInPaise();
        if (samePrice && newQuantity <= order.getRemainingQuantity()) {
            if (newQuantity < order.getRemainingQuantity()) {
                order.reduceQuantityTo(newQuantity); // same object, same place in the queue
            }
            return List.of();
        }

        // Price changed or quantity grew: go to the back of the line as a brand-new order.
        Order replacement = new Order(order.getId(), order.getTrader(), order.getSide(),
                newPrice, newQuantity, lastSequence + 1);
        book.cancel(orderId);
        return submitLimit(replacement);
    }

    // ------------------------------------------------------------------------------------
    // Event-returning API. These wrap the methods above: same rules, but the caller gets a
    // list of facts (Event) instead of only trades, and a refused command comes back as an
    // OrderRejected event instead of an exception, so one bad command cannot crash the caller.
    // ------------------------------------------------------------------------------------

    /**
     * Like {@link #submitLimit}, but returns events: OrderAccepted, then a TradeExecuted per
     * trade, then (for IOC/FOK leftovers) an OrderCancelled. A refused order yields a single
     * OrderRejected.
     */
    public List<Event> processLimit(Order incoming) {
        return process(incoming, OrderType.LIMIT);
    }

    /** Like {@link #submitMarket}, but returns events. See {@link #processLimit}. */
    public List<Event> processMarket(Order incoming) {
        return process(incoming, OrderType.MARKET);
    }

    /** Like {@link #cancel}, but returns OrderCancelled, or OrderRejected if nothing was resting. */
    public List<Event> processCancel(long orderId) {
        Order live = book.restingOrder(orderId);
        if (live == null) {
            return List.of(new Event.OrderRejected(orderId,
                    "order " + orderId + " is not resting on the book"));
        }
        long remaining = live.getRemainingQuantity();
        book.cancel(orderId);
        return List.of(new Event.OrderCancelled(orderId, remaining, CancelReason.REQUESTED));
    }

    /**
     * Like {@link #modify}, but returns OrderModified followed by any TradeExecuted events, or
     * OrderRejected (the order is left exactly as it was) if the request is invalid.
     */
    public List<Event> processModify(long orderId, long newPrice, long newQuantity) {
        Order before = book.restingOrder(orderId);
        if (before == null) {
            return List.of(new Event.OrderRejected(orderId,
                    "order " + orderId + " is not resting on the book"));
        }
        long oldPrice = before.getPriceInPaise();
        long oldRemaining = before.getRemainingQuantity();

        List<Trade> trades;
        try {
            trades = modify(orderId, newPrice, newQuantity);
        } catch (IllegalArgumentException e) {
            return List.of(new Event.OrderRejected(orderId, e.getMessage()));
        }

        boolean keptPriority = newPrice == oldPrice && newQuantity <= oldRemaining;
        List<Event> events = new ArrayList<>();
        events.add(new Event.OrderModified(orderId, newPrice, newQuantity, keptPriority));
        addTradeEvents(events, trades);
        return events;
    }

    private List<Event> process(Order incoming, OrderType expected) {
        List<Trade> trades;
        try {
            trades = expected == OrderType.LIMIT ? submitLimit(incoming) : submitMarket(incoming);
        } catch (IllegalArgumentException e) {
            long id = incoming == null ? -1 : incoming.getId();
            return List.of(new Event.OrderRejected(id, e.getMessage()));
        }

        List<Event> events = new ArrayList<>();
        events.add(new Event.OrderAccepted(
                incoming.getId(),
                incoming.getTrader(),
                incoming.getSide(),
                incoming.getType(),
                incoming.getTimeInForce(),
                incoming.getType() == OrderType.MARKET ? null : incoming.getPriceInPaise(),
                incoming.getOriginalQuantity(),
                incoming.getSequence()));
        addTradeEvents(events, trades);

        long leftover = incoming.getRemainingQuantity();
        if (leftover > 0 && !book.contains(incoming.getId())) { // quantity left, and it did not rest
            CancelReason reason = incoming.getTimeInForce() == TimeInForce.FOK
                    ? CancelReason.FOK_NOT_FILLABLE
                    : CancelReason.UNFILLED_REMAINDER;
            events.add(new Event.OrderCancelled(incoming.getId(), leftover, reason));
        }
        return events;
    }

    private static void addTradeEvents(List<Event> events, List<Trade> trades) {
        for (Trade trade : trades) {
            events.add(new Event.TradeExecuted(trade));
        }
    }

    /**
     * The next arrival number. Create every order with a number from here, so that arrival
     * numbers never clash with the ones the engine hands out itself (modify re-submits).
     */
    public long nextSequence() {
        return ++lastSequence;
    }

    /**
     * Capture the whole engine state as a plain value. O(N) in resting orders. Must be called
     * from the thread that runs the engine.
     */
    public EngineSnapshot snapshot() {
        return new EngineSnapshot(nextTradeId, lastSequence, book.orderStates());
    }

    /**
     * Build a new engine in exactly the state a snapshot describes. The result behaves as if
     * it had processed every command that led to that snapshot.
     *
     * @throws IllegalArgumentException if the snapshot is inconsistent (bad quantities, a
     *         duplicate id, or an order newer than the engine's last sequence number)
     */
    public static MatchingEngine restore(EngineSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (snapshot.nextTradeId() < 1) {
            throw new IllegalArgumentException("next trade id must be at least 1, got "
                    + snapshot.nextTradeId());
        }
        MatchingEngine engine = new MatchingEngine();
        engine.nextTradeId = snapshot.nextTradeId();
        engine.lastSequence = snapshot.lastArrivalSequence();
        for (EngineSnapshot.OrderState state : snapshot.orders()) {
            if (state.sequence() > snapshot.lastArrivalSequence()) {
                throw new IllegalArgumentException("order " + state.id() + " has sequence "
                        + state.sequence() + " beyond the engine's last sequence "
                        + snapshot.lastArrivalSequence());
            }
            engine.book.addResting(Order.restored(state.id(), state.trader(), state.side(),
                    state.priceInPaise(), state.originalQuantity(), state.remainingQuantity(),
                    state.sequence()));
        }
        return engine;
    }

    /** Read access to the book (for printing and tests). */
    public OrderBook book() {
        return book;
    }

    /**
     * The matching loop shared by every order type: trade against the best resting
     * orders while the prices allow it and the incoming order still has quantity.
     */
    private List<Trade> match(Order incoming) {
        List<Trade> trades = new ArrayList<>();
        Side restingSide = incoming.getSide().opposite();

        while (!incoming.isFullyFilled()) {
            PriceLevel bestLevel = book.bestLevel(restingSide);
            if (bestLevel == null || !crosses(incoming, bestLevel.getPriceInPaise())) {
                break; // nobody left, or the best price is too far away
            }

            Order resting = bestLevel.peekFirst(); // oldest order at the best price
            long quantity = Math.min(incoming.getRemainingQuantity(), resting.getRemainingQuantity());

            incoming.fill(quantity);
            resting.fill(quantity);
            trades.add(newTrade(incoming, resting, quantity));

            if (resting.isFullyFilled()) {
                book.removeResting(resting); // also drops the empty level and the index entry
            }
        }
        return trades;
    }

    /**
     * How much the incoming order could trade right now, WITHOUT trading anything.
     * Stops counting as soon as it reaches what the order needs.
     */
    private long availableToTrade(Order incoming) {
        long needed = incoming.getRemainingQuantity();
        long available = 0;
        for (PriceLevel level : book.levelsBestFirst(incoming.getSide().opposite())) {
            if (!crosses(incoming, level.getPriceInPaise())) {
                break; // every level after this one is even worse
            }
            available += level.totalQuantity();
            if (available >= needed) {
                break;
            }
        }
        return available;
    }

    private void validate(Order incoming, OrderType expected) {
        if (incoming == null) {
            throw new IllegalArgumentException("order must not be null");
        }
        if (incoming.getType() != expected) {
            throw new IllegalArgumentException(
                    "expected a " + expected + " order but got " + incoming.getType());
        }
        if (book.contains(incoming.getId())) {
            throw new IllegalArgumentException("duplicate order id " + incoming.getId());
        }
    }

    /**
     * Time priority is the order of arrival numbers inside a price level, so a GTC order
     * (the only kind that can rest) must have a number greater than the newest order already
     * queued at its price. Checked BEFORE matching, so a rejected order changes nothing.
     * Matching only touches the opposite side, so this level cannot change in between.
     */
    private void requireQueueOrder(Order incoming) {
        if (incoming.getTimeInForce() != TimeInForce.GTC) {
            return; // IOC and FOK never rest
        }
        PriceLevel sameLevel = book.levelAt(incoming.getSide(), incoming.getPriceInPaise());
        if (sameLevel != null && sameLevel.peekLast().getSequence() >= incoming.getSequence()) {
            throw new IllegalArgumentException("sequence " + incoming.getSequence()
                    + " is not greater than " + sameLevel.peekLast().getSequence()
                    + ", the newest order queued at " + incoming.getPriceInPaise()
                    + "; take numbers from nextSequence()");
        }
    }

    /** Would an incoming order trade against a resting order at this price? */
    private static boolean crosses(Order incoming, long restingPrice) {
        if (incoming.getType() == OrderType.MARKET) {
            return true; // a market order accepts any price
        }
        return incoming.getSide() == Side.BUY
                ? restingPrice <= incoming.getPriceInPaise()   // buyer pays up to their limit
                : restingPrice >= incoming.getPriceInPaise();  // seller wants at least their limit
    }

    private Trade newTrade(Order incoming, Order resting, long quantity) {
        Order buy = incoming.getSide() == Side.BUY ? incoming : resting;
        Order sell = incoming.getSide() == Side.BUY ? resting : incoming;
        return new Trade(
                nextTradeId++,
                buy.getId(),
                sell.getId(),
                buy.getTrader(),
                sell.getTrader(),
                resting.getPriceInPaise(), // trade price = RESTING order's price
                quantity,
                incoming.getSequence());
    }
}
