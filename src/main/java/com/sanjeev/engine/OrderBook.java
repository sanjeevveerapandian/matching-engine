package com.sanjeev.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The noticeboard: every resting (waiting) order, grouped by price.
 *
 * <p>Bids (buyers) are sorted HIGHEST price first, because the best buyer is the one
 * willing to pay the most. Asks (sellers) are sorted LOWEST price first, because the
 * best seller is the cheapest. So in both maps, the first entry is the "best" price.
 *
 * <p>This phase only rests orders. Matching comes later.
 */
public final class OrderBook {

    /** One row of the ladder: a price and the total quantity waiting there. */
    public record Level(long priceInPaise, long totalQuantity) { }

    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();

    /** Put an order on the book. No matching. O(log P), P = number of price levels. */
    public void addResting(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("order must not be null");
        }
        TreeMap<Long, PriceLevel> side = sideOf(order.getSide());
        side.computeIfAbsent(order.getPriceInPaise(), PriceLevel::new).add(order);
    }

    /** Highest buy price, or null if there are no bids. O(log P). */
    public Long bestBid() {
        return bids.isEmpty() ? null : bids.firstKey();
    }

    /** Lowest sell price, or null if there are no asks. O(log P). */
    public Long bestAsk() {
        return asks.isEmpty() ? null : asks.firstKey();
    }

    /** Gap between best ask and best bid, or null if either side is empty. O(log P). */
    public Long spread() {
        Long bid = bestBid();
        Long ask = bestAsk();
        return (bid == null || ask == null) ? null : ask - bid;
    }

    /**
     * The top {@code levels} price levels of one side, best price first. O(levels * n),
     * where n is orders per level (each level's total is summed).
     */
    public List<Level> depth(Side side, int levels) {
        if (levels < 0) {
            throw new IllegalArgumentException("levels must not be negative, got " + levels);
        }
        List<Level> result = new ArrayList<>();
        for (PriceLevel level : sideOf(side).values()) {
            if (result.size() == levels) {
                break;
            }
            result.add(new Level(level.getPriceInPaise(), level.totalQuantity()));
        }
        return result;
    }

    /** The queue at one exact price, or null if none. Package-private: for tests, not for callers. */
    PriceLevel levelAt(Side side, long priceInPaise) {
        return sideOf(side).get(priceInPaise);
    }

    /** Which map holds this side. */
    private TreeMap<Long, PriceLevel> sideOf(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    /**
     * Prints like an exchange ladder: asks on top (highest to lowest), the spread,
     * then bids (highest to lowest). The best ask and best bid meet in the middle.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        // asks map is lowest-first, so walk it backwards to print highest-first
        for (Map.Entry<Long, PriceLevel> e : asks.descendingMap().entrySet()) {
            sb.append(String.format("ASK %8d x %d%n", e.getKey(), e.getValue().totalQuantity()));
        }
        Long spread = spread();
        sb.append("---- spread: ").append(spread == null ? "n/a" : spread).append(" ----")
                .append(System.lineSeparator());
        for (Map.Entry<Long, PriceLevel> e : bids.entrySet()) {
            sb.append(String.format("BID %8d x %d%n", e.getKey(), e.getValue().totalQuantity()));
        }
        return sb.toString();
    }
}
