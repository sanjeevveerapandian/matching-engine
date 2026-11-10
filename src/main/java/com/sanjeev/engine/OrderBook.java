package com.sanjeev.engine;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The noticeboard: every resting (waiting) order, grouped by price.
 *
 * <p>Bids (buyers) are kept HIGHEST price first, because the best buyer is the one
 * willing to pay the most. Asks (sellers) are kept LOWEST price first, because the
 * best seller is the cheapest. So on both sides, the first level is the "best" price.
 * Each side is a {@link PriceLevelMap}: sorted primitive arrays, with no boxing.
 *
 * <p>Besides the two price sides there is an INDEX: order id -> order, for every resting
 * order. It lets us find any order instantly by id (needed to cancel). Rule: the index
 * holds exactly the orders that are resting, no more, no fewer.
 */
public final class OrderBook {

    /** One row of the ladder: a price and the total quantity waiting there (not the queue: see {@link PriceLevel}). */
    public record LevelSummary(long priceInPaise, long totalQuantity) { }

    private final PriceLevelMap bids = new PriceLevelMap(true);   // highest price is best
    private final PriceLevelMap asks = new PriceLevelMap(false);  // lowest price is best
    private final Map<Long, Order> index = new HashMap<>();

    /**
     * Put a LIMIT order on the book. No matching. O(log P) to find its price level, P = number of
     * price levels; creating a NEW level also shifts array elements, O(P) worst case (little, near
     * the best price, where most orders arrive).
     *
     * <p>Package-private on purpose: only the engine decides what rests. A public method would let
     * any caller build a crossed book or park an IOC order forever.
     *
     * @throws IllegalArgumentException if null, not a good-till-cancelled limit order, already
     *         completely filled, or its id is already resting
     */
    void addResting(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("order must not be null");
        }
        if (order.getType() != OrderType.LIMIT) {
            throw new IllegalArgumentException("only limit orders can rest on the book");
        }
        if (order.getTimeInForce() != TimeInForce.GTC) {
            throw new IllegalArgumentException(
                    "only good-till-cancelled orders can rest on the book, got " + order.getTimeInForce());
        }
        if (order.isFullyFilled()) {
            throw new IllegalArgumentException("an order with nothing left to trade cannot rest");
        }
        if (index.containsKey(order.getId())) {
            throw new IllegalArgumentException("duplicate order id " + order.getId());
        }
        sideOf(order.getSide()).getOrCreate(order.getPriceInPaise()).add(order);
        index.put(order.getId(), order);
    }

    /** Is an order with this id resting on the book right now? O(1). */
    public boolean contains(long orderId) {
        return index.containsKey(orderId);
    }

    /**
     * Cancel a resting order.
     *
     * <p>Package-private: callers cancel through {@link MatchingEngine#cancel}.
     *
     * @return true if it was resting and is now removed; false if the id is unknown or the
     *         order is no longer resting (already filled or already cancelled)
     */
    boolean cancel(long orderId) {
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
        return bids.isEmpty() ? null : bids.bestPrice();
    }

    /** Lowest sell price, or null if there are no asks. O(log P). */
    public Long bestAsk() {
        return asks.isEmpty() ? null : asks.bestPrice();
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
    public List<LevelSummary> depth(Side side, int levels) {
        if (levels < 0) {
            throw new IllegalArgumentException("levels must not be negative, got " + levels);
        }
        List<LevelSummary> result = new ArrayList<>();
        for (PriceLevel level : sideOf(side).values()) {
            if (result.size() == levels) {
                break;
            }
            result.add(new LevelSummary(level.getPriceInPaise(), level.totalQuantity()));
        }
        return result;
    }

    /** The best (first) price level on one side, or null if that side is empty. O(log P). */
    PriceLevel bestLevel(Side side) {
        return sideOf(side).best();
    }

    /** Remove a price level from the map if it has no orders left. O(log P). */
    void removeLevelIfEmpty(Side side, long priceInPaise) {
        PriceLevelMap map = sideOf(side);
        PriceLevel level = map.get(priceInPaise);
        if (level != null && level.isEmpty()) {
            map.remove(priceInPaise);
        }
    }

    /** The queue at one exact price, or null if none. Package-private: for tests, not for callers. */
    PriceLevel levelAt(Side side, long priceInPaise) {
        return sideOf(side).get(priceInPaise);
    }

    /** The resting order with this id, or null if it is not resting. O(1). */
    public Order restingOrder(long orderId) {
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

    /**
     * A stable fingerprint of the book, so two books can be compared by comparing two strings.
     *
     * <p>It is SHA-256 over every resting order in book order (bids best price first, then asks
     * best price first, each level oldest order first). For each side: the number of orders,
     * then for each order its id, side, price, remaining quantity and sequence number. Every
     * number is written as 8 bytes, big-endian, so the result does not depend on the machine,
     * the JVM or Java's {@code hashCode}. Same resting orders in the same order give the same
     * hash; change any of those five fields, or the queue order, and the hash changes.
     *
     * <p>It does NOT cover the trader name or the original quantity, nor the engine's counters;
     * {@link EngineSnapshot} carries those. O(N) in resting orders.
     *
     * @return 64 lowercase hex characters
     */
    public String stateHash() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform must provide SHA-256", e);
        }
        for (Side side : Side.values()) {
            long count = 0;
            for (PriceLevel level : sideOf(side).values()) {
                count += level.size();
            }
            putLong(digest, count);
            for (PriceLevel level : sideOf(side).values()) {
                for (Order order : level.orders()) {
                    putLong(digest, order.getId());
                    digest.update((byte) order.getSide().ordinal());
                    putLong(digest, order.getPriceInPaise());
                    putLong(digest, order.getRemainingQuantity());
                    putLong(digest, order.getSequence());
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void putLong(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            digest.update((byte) (value >>> shift));
        }
    }

    /** Every resting order in book order, for snapshots. */
    List<EngineSnapshot.OrderState> orderStates() {
        List<EngineSnapshot.OrderState> states = new ArrayList<>();
        for (Side side : Side.values()) {
            for (PriceLevel level : sideOf(side).values()) {
                for (Order order : level.orders()) {
                    states.add(new EngineSnapshot.OrderState(order.getId(), order.getTrader(),
                            order.getSide(), order.getPriceInPaise(), order.getOriginalQuantity(),
                            order.getRemainingQuantity(), order.getSequence()));
                }
            }
        }
        return states;
    }

    /** How many entries the index holds. Package-private: for tests. */
    int indexSize() {
        return index.size();
    }

    /** Counts resting orders by walking the real price levels, ignoring the index. O(P). */
    public int restingOrderCount() {
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
    private PriceLevelMap sideOf(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    /**
     * Prints like an exchange ladder: asks on top (highest to lowest), the spread,
     * then bids (highest to lowest). The best ask and best bid meet in the middle.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        // the ask side is printed highest price first, i.e. WORST first
        for (PriceLevel level : asks.worstFirst()) {
            sb.append(String.format(Locale.ROOT, "ASK %8d x %d%n", level.getPriceInPaise(), level.totalQuantity()));
        }
        Long spread = spread();
        sb.append("---- spread: ").append(spread == null ? "n/a" : spread).append(" ----")
                .append(System.lineSeparator());
        for (Map.Entry<Long, PriceLevel> e : bids.entrySet()) {
            sb.append(String.format(Locale.ROOT, "BID %8d x %d%n", e.getKey(), e.getValue().totalQuantity()));
        }
        return sb.toString();
    }
}
