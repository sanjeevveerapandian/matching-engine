package com.sanjeev.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Takes incoming limit orders and trades them against the book.
 *
 * <p>Rules (never break these):
 * <ul>
 *   <li>Best price first, then oldest order first (price-time priority).</li>
 *   <li>The trade happens at the RESTING order's price, not the incoming one's.</li>
 *   <li>Whatever quantity is left after matching rests in the book.</li>
 * </ul>
 *
 * <p>Single-threaded on purpose: one order is fully processed before the next starts.
 */
public final class MatchingEngine {

    private final OrderBook book = new OrderBook();
    private long nextTradeId = 1;

    /**
     * Match {@code incoming} against the opposite side, then rest any leftover.
     *
     * @return the trades, in the order they happened (empty if nothing matched)
     */
    public List<Trade> submitLimit(Order incoming) {
        if (incoming == null) {
            throw new IllegalArgumentException("order must not be null");
        }

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
                bestLevel.removeFirst();
                book.removeLevelIfEmpty(restingSide, bestLevel.getPriceInPaise());
            }
        }

        if (!incoming.isFullyFilled()) {
            book.addResting(incoming);
        }
        return trades;
    }

    /** Read access to the book (for printing and tests). */
    public OrderBook book() {
        return book;
    }

    /** Would an incoming order trade against a resting order at this price? */
    private static boolean crosses(Order incoming, long restingPrice) {
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
