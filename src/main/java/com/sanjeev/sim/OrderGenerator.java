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
    private final int priceNoise;
    private final int passivePercent;
    private long nextOrderId = 1;

    /**
     * @param sequences source of arrival numbers: pass the engine's {@code nextSequence},
     *                  so our numbers never clash with the ones the engine hands out itself
     */
    public OrderGenerator(long seed, OrderBook book, LongSupplier sequences) {
        this(seed, book, sequences, PRICE_NOISE);
    }

    /**
     * @param priceNoise how far (in paise) a new order's price may land from the current mid;
     *                   a bigger value spreads orders over more price levels and gives a deeper book
     */
    public OrderGenerator(long seed, OrderBook book, LongSupplier sequences, int priceNoise) {
        this(seed, book, sequences, priceNoise, 0);
    }

    /**
     * @param passivePercent the share (0 to 100) of new limit orders that are PASSIVE: a buy is
     *                       priced below the mid and a sell above it, 1 to {@code priceNoise}
     *                       paise away, so it rests instead of trading. Real order flow is mostly
     *                       passive; with 0 (the default) prices fall on either side of the mid
     *                       and most orders cross at once, which keeps the book almost empty.
     */
    public OrderGenerator(long seed, OrderBook book, LongSupplier sequences, int priceNoise,
                          int passivePercent) {
        if (priceNoise < 1) {
            throw new IllegalArgumentException("priceNoise must be at least 1, got " + priceNoise);
        }
        if (passivePercent < 0 || passivePercent > 100) {
            throw new IllegalArgumentException("passivePercent must be 0 to 100, got " + passivePercent);
        }
        this.random = new Random(seed);
        this.book = book;
        this.sequences = sequences;
        this.priceNoise = priceNoise;
        this.passivePercent = passivePercent;
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
        String trader = randomTrader();
        Side side = randomSide();
        long price = quotePrice(side);
        long quantity = randomQuantity();
        Order order = Order.limit(id, trader, side, price, quantity, sequences.getAsLong(), tif);
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
        return new Operation.Modify(orderId, quotePrice(live.getSide()), randomQuantity()); // new price
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

    /**
     * Near the current mid price, clamped to the allowed band. A passive order (see the
     * constructor) goes on its own side of the mid; others land on either side. With
     * {@code passivePercent == 0} no extra random number is drawn, so the stream of operations
     * is exactly what it always was.
     */
    private long quotePrice(Side side) {
        long mid = currentMid();
        long price;
        if (passivePercent > 0 && random.nextInt(100) < passivePercent) {
            long away = 1 + random.nextInt(priceNoise);
            price = side == Side.BUY ? mid - away : mid + away;
        } else {
            price = mid + random.nextInt(2 * priceNoise + 1) - priceNoise;
        }
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
