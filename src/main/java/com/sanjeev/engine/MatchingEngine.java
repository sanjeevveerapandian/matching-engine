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
 *   <li>A LIMIT order rests whatever quantity is left after matching.</li>
 *   <li>A MARKET order NEVER rests: whatever is left after matching is cancelled.</li>
 * </ul>
 *
 * <p>Single-threaded on purpose: one order is fully processed before the next starts.
 */
public final class MatchingEngine {

    private final OrderBook book = new OrderBook();
    private long nextTradeId = 1;

    /**
     * Match a LIMIT order against the opposite side, then rest any leftover.
     *
     * @return the trades, in the order they happened (empty if nothing matched)
     * @throws IllegalArgumentException if null, not a limit order, or the id is already resting
     */
    public List<Trade> submitLimit(Order incoming) {
        validate(incoming, OrderType.LIMIT);

        List<Trade> trades = match(incoming);
        if (!incoming.isFullyFilled()) {
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

    /** Read access to the book (for printing and tests). */
    public OrderBook book() {
        return book;
    }

    /**
     * The matching loop shared by limit and market orders: trade against the best resting
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
