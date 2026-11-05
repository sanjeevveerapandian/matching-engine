package com.sanjeev.engine;

import java.util.Map;

/**
 * Self-check for an {@link OrderBook}: verifies the facts that must ALWAYS be true.
 *
 * <p>If any fact is false, the book is corrupt (a bug somewhere), and we want to find out
 * immediately, right after the operation that broke it, not hours later from a strange
 * trade. Call {@link #check(OrderBook)} after every operation in tests.
 *
 * <p>Cost: O(N) in the number of resting orders, so it is for tests and debugging.
 */
public final class BookInvariants {

    private BookInvariants() { }

    /**
     * @throws IllegalStateException describing the first broken rule it finds
     */
    public static void check(OrderBook book) {
        // 1. the index and the book agree on how many orders exist
        int resting = book.restingOrderCount();
        if (book.indexSize() != resting) {
            throw broken("index holds " + book.indexSize() + " orders but the book has " + resting);
        }

        for (Side side : Side.values()) {
            for (Map.Entry<Long, PriceLevel> entry : book.levelsOf(side).entrySet()) {
                long price = entry.getKey();
                PriceLevel level = entry.getValue();

                // 2. no empty price levels
                if (level.isEmpty()) {
                    throw broken("empty price level at " + price + " on the " + side + " side");
                }

                long previousSequence = Long.MIN_VALUE;
                for (Order order : level.orders()) {
                    // 3. every order sits in the level that matches its own price
                    if (order.getPriceInPaise() != price || level.getPriceInPaise() != price) {
                        throw broken(order + " sits in a level priced " + price);
                    }
                    // 4. no finished or negative orders still resting
                    if (order.getRemainingQuantity() <= 0) {
                        throw broken(order + " has remaining quantity "
                                + order.getRemainingQuantity() + " but is still resting");
                    }
                    // 5. time priority: strictly increasing sequence inside a level
                    if (order.getSequence() <= previousSequence) {
                        throw broken("orders at " + price + " are not in increasing sequence order: "
                                + order + " comes after sequence " + previousSequence);
                    }
                    previousSequence = order.getSequence();
                    // 6. the index holds this very order (same object, not just the same id)
                    if (book.rawIndex().get(order.getId()) != order) {
                        throw broken("index does not hold the resting " + order);
                    }
                }
            }
        }

        // 7. the book must never be crossed: if best bid >= best ask, they should have traded
        Long bestBid = book.bestBid();
        Long bestAsk = book.bestAsk();
        if (bestBid != null && bestAsk != null && bestBid >= bestAsk) {
            throw broken("book is crossed: best bid " + bestBid + " >= best ask " + bestAsk);
        }
    }

    private static IllegalStateException broken(String message) {
        return new IllegalStateException("Book invariant broken: " + message);
    }
}
