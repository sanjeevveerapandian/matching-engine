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
