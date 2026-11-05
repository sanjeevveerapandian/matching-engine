package com.sanjeev.sim;

import com.sanjeev.engine.Order;
import com.sanjeev.engine.OrderBook;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * Produces a repeatable, realistic stream of operations.
 *
 * <p>Mix: 60% limit orders, 10% market orders, 20% cancels, 10% modifies. Prices cluster
 * around the current mid price, always inside 3000 paise +-5%. Given the same seed and the
 * same book behaviour, the stream is exactly the same every run (java.util.Random with a
 * fixed seed is a deterministic formula, not real randomness).
 */
public final class OrderGenerator {

    static final long CENTER_PRICE = 3000;
    static final long MIN_PRICE = 2850;   // 3000 - 5%
    static final long MAX_PRICE = 3150;   // 3000 + 5%
    static final int PRICE_NOISE = 40;    // how far from the mid a new order may land
    static final int MAX_QUANTITY = 100;

    private static final String[] TRADERS = {"Ravi", "Anu", "Priya", "Kumar", "Meena"};

    private final Random random;
    private final OrderBook book;
    /** Ids of GTC orders that may still be resting; dead ones are dropped when noticed. */
    private final List<Long> maybeLive = new ArrayList<>();
    private final LongSupplier sequences;
    private long nextOrderId = 1;

    /**
     * @param sequences source of arrival numbers: pass the engine's {@code nextSequence},
     *                  so our numbers never clash with the ones the engine hands out itself
     */
    public OrderGenerator(long seed, OrderBook book, LongSupplier sequences) {
        this.random = new Random(seed);
        this.book = book;
        this.sequences = sequences;
    }

    /** The next operation. Never returns null. */
    public Operation next() {
        int roll = random.nextInt(100);
        if (roll < 60) {
            return new Operation.SubmitLimit(newLimitOrder());
        }
        if (roll < 70) {
            return new Operation.SubmitMarket(newMarketOrder());
        }
        OptionalLong live = pickLiveOrder();
        if (live.isEmpty()) {
            return new Operation.SubmitLimit(newLimitOrder()); // nothing to cancel or modify yet
        }
        return roll < 90 ? new Operation.Cancel(live.getAsLong()) : newModify(live.getAsLong());
    }

    private Order newLimitOrder() {
        long id = nextOrderId++;
        TimeInForce tif = pickTimeInForce();
        Order order = Order.limit(id, randomTrader(), randomSide(), quotePrice(), randomQuantity(),
                sequences.getAsLong(), tif);
        if (tif == TimeInForce.GTC) {
            maybeLive.add(id);
        }
        return order;
    }

    private Order newMarketOrder() {
        long id = nextOrderId++;
        return Order.market(id, randomTrader(), randomSide(), randomQuantity(), sequences.getAsLong());
    }

    private Operation.Modify newModify(long orderId) {
        Order live = book.restingOrder(orderId);
        long price = live.getPriceInPaise();
        long remaining = live.getRemainingQuantity();
        int kind = random.nextInt(10);
        if (kind < 4 && remaining > 1) {                       // smaller, same price
            return new Operation.Modify(orderId, price, 1 + random.nextInt((int) remaining - 1));
        }
        if (kind < 7) {                                        // bigger, same price
            return new Operation.Modify(orderId, price, remaining + 1 + random.nextInt(50));
        }
        return new Operation.Modify(orderId, quotePrice(), randomQuantity()); // new price
    }

    /** A random order id that is resting right now, or empty if there is none. */
    private OptionalLong pickLiveOrder() {
        while (!maybeLive.isEmpty()) {
            int index = random.nextInt(maybeLive.size());
            long id = maybeLive.get(index);
            if (book.contains(id)) {
                return OptionalLong.of(id);
            }
            // filled or cancelled meanwhile: forget it (swap with last for O(1) removal)
            maybeLive.set(index, maybeLive.get(maybeLive.size() - 1));
            maybeLive.remove(maybeLive.size() - 1);
        }
        return OptionalLong.empty();
    }

    /** Near the current mid price, clamped to the allowed band. */
    private long quotePrice() {
        long mid = currentMid();
        long price = mid + random.nextInt(2 * PRICE_NOISE + 1) - PRICE_NOISE;
        return Math.max(MIN_PRICE, Math.min(MAX_PRICE, price));
    }

    private long currentMid() {
        Long bid = book.bestBid();
        Long ask = book.bestAsk();
        if (bid != null && ask != null) {
            return (bid + ask) / 2;
        }
        if (bid != null) {
            return bid;
        }
        if (ask != null) {
            return ask;
        }
        return CENTER_PRICE;
    }

    private TimeInForce pickTimeInForce() {
        int roll = random.nextInt(100);
        if (roll < 85) {
            return TimeInForce.GTC;
        }
        return roll < 95 ? TimeInForce.IOC : TimeInForce.FOK;
    }

    private Side randomSide() {
        return random.nextBoolean() ? Side.BUY : Side.SELL;
    }

    private long randomQuantity() {
        return 1 + random.nextInt(MAX_QUANTITY);
    }

    private String randomTrader() {
        return TRADERS[random.nextInt(TRADERS.length)];
    }
}
