package com.sanjeev.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
 * <p>Besides the two price maps there is an INDEX: order id -> order, for every resting
 * order. It lets us find any order instantly by id (needed to cancel). Rule: the index
 * holds exactly the orders that are resting, no more, no fewer.
 */
public final class OrderBook {

    /** One row of the ladder: a price and the total quantity waiting there. */
    public record Level(long priceInPaise, long totalQuantity) { }

    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();
    private final Map<Long, Order> index = new HashMap<>();

    /**
     * Put a LIMIT order on the book. No matching. O(log P), P = number of price levels.
     *
     * @throws IllegalArgumentException if null, not a limit order, or its id is already resting
     */
    public void addResting(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("order must not be null");
        }
        if (order.getType() != OrderType.LIMIT) {
            throw new IllegalArgumentException("only limit orders can rest on the book");
        }
        if (index.containsKey(order.getId())) {
            throw new IllegalArgumentException("duplicate order id " + order.getId());
        }
        sideOf(order.getSide()).computeIfAbsent(order.getPriceInPaise(), PriceLevel::new).add(order);
        index.put(order.getId(), order);
    }

    /** Is an order with this id resting on the book right now? O(1). */
    public boolean contains(long orderId) {
        return index.containsKey(orderId);
    }

    /**
     * Cancel a resting order.
     *
     * @return true if it was resting and is now removed; false if the id is unknown or the
     *         order is no longer resting (already filled or already cancelled)
     */
    public boolean cancel(long orderId) {
        Order order = index.get(orderId); // O(1): no scanning of the book
        if (order == null) {
            return false;
        }
        removeResting(order);
        return true;
    }

    /**
     * Remove a resting order from its level, the level if it became empty, and the index.
     * Used for cancels and for orders that were completely filled.
     */
    void removeResting(Order order) {
        Side side = order.getSide();
        long price = order.getPriceInPaise();
        PriceLevel level = sideOf(side).get(price);
        if (level == null || !level.remove(order)) {
            throw new IllegalStateException("index out of sync with book for " + order);
        }
        removeLevelIfEmpty(side, price);
        index.remove(order.getId());
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

    /** The best (first) price level on one side, or null if that side is empty. O(log P). */
    PriceLevel bestLevel(Side side) {
        TreeMap<Long, PriceLevel> map = sideOf(side);
        return map.isEmpty() ? null : map.firstEntry().getValue();
    }

    /** Remove a price level from the map if it has no orders left. O(log P). */
    void removeLevelIfEmpty(Side side, long priceInPaise) {
        TreeMap<Long, PriceLevel> map = sideOf(side);
        PriceLevel level = map.get(priceInPaise);
        if (level != null && level.isEmpty()) {
            map.remove(priceInPaise);
        }
    }

    /** The queue at one exact price, or null if none. Package-private: for tests, not for callers. */
    PriceLevel levelAt(Side side, long priceInPaise) {
        return sideOf(side).get(priceInPaise);
    }

    /** The resting order with this id, or null. O(1). Package-private: for the engine. */
    Order restingOrder(long orderId) {
        return index.get(orderId);
    }

    /** Price levels of one side, best price first, read-only. Package-private: for the engine. */
    Collection<PriceLevel> levelsBestFirst(Side side) {
        return Collections.unmodifiableCollection(sideOf(side).values());
    }

    /** The real price map of one side. Package-private: for BookInvariants and tests ONLY. */
    Map<Long, PriceLevel> levelsOf(Side side) {
        return sideOf(side);
    }

    /** The real index map. Package-private: for BookInvariants and tests ONLY. */
    Map<Long, Order> rawIndex() {
        return index;
    }

    /** How many entries the index holds. Package-private: for tests. */
    int indexSize() {
        return index.size();
    }

    /** Counts resting orders by walking the real price levels, ignoring the index. For tests. */
    int restingOrderCount() {
        int count = 0;
        for (PriceLevel level : bids.values()) {
            count += level.size();
        }
        for (PriceLevel level : asks.values()) {
            count += level.size();
        }
        return count;
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
