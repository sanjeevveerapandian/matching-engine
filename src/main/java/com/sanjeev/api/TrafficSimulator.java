package com.sanjeev.api;

import com.sanjeev.engine.OrderType;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Makes up market activity so the live screen has something to show. It is a client like any other:
 * it places, cancels and modifies orders by calling the exchange service, so every simulated order
 * goes through the sequencer and the log, exactly like a real one.
 *
 * <p>Mix: about 55% limit orders, 10% market orders, 25% cancels and 10% modifies. Most limit
 * orders are PASSIVE (a buy below the current mid price, a sell above it) so they rest and the book
 * grows a ladder; some cross the spread and trade. The same seed gives the same sequence of
 * requests.
 */
class TrafficSimulator {

    /** The most operations one call may ask for. */
    static final int MAX_COUNT = 10_000;

    private static final String[] TRADERS = {"Ravi", "Anu", "Priya", "Kumar", "Meena", "Suresh", "Divya"};
    private static final long DEFAULT_PRICE_PAISE = 4000; // Rs 40.00, used when the book is empty

    private final ExchangeService exchange;

    TrafficSimulator(ExchangeService exchange) {
        this.exchange = exchange;
    }

    /**
     * Run {@code count} operations.
     *
     * @throws ExchangeUnavailableException if the exchange stops answering part-way
     */
    Domain.SimulationResult run(int count, long seed) {
        Random random = new Random(seed);
        List<Long> resting = new ArrayList<>(); // ids of orders of ours that probably still rest
        int limitOrders = 0;
        int marketOrders = 0;
        int cancels = 0;
        int modifies = 0;
        int notFound = 0;
        int rejected = 0;
        int trades = 0;
        long reference = referencePaise();

        for (int i = 0; i < count; i++) {
            if (i % 50 == 0) {
                reference = referencePaise(); // follow the market as it moves
            }
            int roll = random.nextInt(100);
            if (roll >= 65 && resting.isEmpty()) {
                roll = 0; // nothing to cancel or modify yet: place an order instead
            }
            try {
                if (roll < 55) {
                    limitOrders++; // counted before the call, so a refused order still has its kind
                    Domain.PlacedOrder placed = exchange.placeOrder(randomLimitOrder(random, reference));
                    trades += placed.trades().size();
                    if (placed.restingQuantity() > 0) {
                        resting.add(placed.orderId());
                    }
                } else if (roll < 65) {
                    Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                    marketOrders++;
                    Domain.PlacedOrder placed = exchange.placeOrder(new Domain.NewOrderSpec(
                            trader(random), side, OrderType.MARKET, TimeInForce.IOC, null, 1 + random.nextInt(30)));
                    trades += placed.trades().size();
                } else if (roll < 90) {
                    long id = resting.remove(random.nextInt(resting.size()));
                    cancels++;
                    exchange.cancelOrder(id);
                } else {
                    int index = random.nextInt(resting.size());
                    modifies++;
                    Domain.ModifiedOrder modified;
                    try {
                        modified = exchange.modifyOrder(resting.get(index),
                                Math.max(1, reference + random.nextInt(41) - 20), 1 + random.nextInt(30));
                    } catch (OrderNotFoundException e) {
                        resting.remove(index); // it traded away since we last looked
                        throw e;
                    }
                    trades += modified.trades().size();
                }
            } catch (OrderNotFoundException e) {
                notFound++;
            } catch (OrderRejectedException e) {
                rejected++;
            }
        }
        return new Domain.SimulationResult(count, limitOrders, marketOrders, cancels, modifies, notFound,
                rejected, trades);
    }

    private static Domain.NewOrderSpec randomLimitOrder(Random random, long reference) {
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        boolean passive = random.nextInt(100) < 70;
        // passive: 1 to 40 paise on its own side of the mid (it rests). Otherwise it reaches up to 20
        // paise THROUGH the mid and trades.
        long away = passive ? 1 + random.nextInt(40) : -random.nextInt(21);
        long price = side == Side.BUY ? reference - away : reference + away;
        return new Domain.NewOrderSpec(trader(random), side, OrderType.LIMIT, TimeInForce.GTC,
                Math.max(1, price), 1 + random.nextInt(50));
    }

    private static String trader(Random random) {
        return TRADERS[random.nextInt(TRADERS.length)];
    }

    /** The middle of the best bid and ask, or whichever exists, or a default for an empty book. */
    private long referencePaise() {
        Domain.BookSnapshot book = exchange.book(1);
        if (book.bestBid() != null && book.bestAsk() != null) {
            return (book.bestBid() + book.bestAsk()) / 2;
        }
        if (book.bestBid() != null) {
            return book.bestBid();
        }
        if (book.bestAsk() != null) {
            return book.bestAsk();
        }
        return DEFAULT_PRICE_PAISE;
    }
}
