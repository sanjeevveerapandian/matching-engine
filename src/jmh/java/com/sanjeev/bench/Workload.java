package com.sanjeev.bench;

import com.sanjeev.engine.EngineSnapshot;
import com.sanjeev.engine.Event;
import com.sanjeev.engine.MatchingEngine;
import com.sanjeev.engine.Order;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;
import com.sanjeev.sim.Operation;
import com.sanjeev.sim.OrderGenerator;
import java.util.List;

/**
 * The input for the benchmarks: a seeded, repeatable stream of operations, plus a pre-built book.
 *
 * <p>The stream comes from {@link OrderGenerator}: 60% limit orders priced around the current
 * mid, 10% market orders, 20% cancels and 10% modifies, where cancels and modifies only ever
 * target orders that are really resting (so none of them is a cheap "unknown id" rejection).
 * Limit orders are 85% GTC, 10% IOC, 5% FOK.
 *
 * <p>Built in two stages, both fixed by the seed:
 * <ol>
 *   <li>The first PREFILL operations build a book with some depth. Its state is kept as a
 *       snapshot, so every benchmark invocation can start from an identical, already-populated
 *       book in a fraction of a millisecond (and that reset is NOT timed).</li>
 *   <li>The next operations are recorded into plain arrays; those are the ones measured.</li>
 * </ol>
 *
 * <p>The generator looks at the live book to choose its operations, so it is driven once against
 * a scratch engine while the stream is recorded. The engine is deterministic, so replaying the
 * recorded operations on a restored engine reproduces exactly the same evolution.
 *
 * <p>The timed region is the engine's work only: build the Order, match it, produce the events.
 * Nothing here logs, writes files, or uses threads.
 */
final class Workload {

    static final long SEED = 42;
    static final int PREFILL = 20_000;
    /** A wide price spread (the full +-5% band), so orders land on many price levels. */
    static final int PRICE_NOISE = 150;
    /** Share of limit orders that are passive (rest below/above the mid instead of trading). */
    static final int PASSIVE_PERCENT = Integer.getInteger("bench.passive", 80);

    private static final int LIMIT = 0;
    private static final int MARKET = 1;
    private static final int CANCEL = 2;
    private static final int MODIFY = 3;

    /** The book (and engine counters) after the prefill. Immutable, so safely reusable. */
    final EngineSnapshot snapshot;
    /** How many measured operations are available. */
    final int size;

    private final int[] kind;
    private final long[] id;
    private final long[] price;
    private final long[] quantity;
    private final Side[] side;
    private final TimeInForce[] timeInForce;
    private final String[] trader;

    Workload(int measuredOperations) {
        this.size = measuredOperations;
        kind = new int[size];
        id = new long[size];
        price = new long[size];
        quantity = new long[size];
        side = new Side[size];
        timeInForce = new TimeInForce[size];
        trader = new String[size];

        MatchingEngine scratch = new MatchingEngine();
        OrderGenerator generator = new OrderGenerator(SEED, scratch.book(), scratch::nextSequence,
                PRICE_NOISE, PASSIVE_PERCENT);
        EngineSnapshot atPrefillEnd = null;
        for (int i = 0; i < PREFILL + size; i++) {
            Operation operation = generator.next();
            int slot = i - PREFILL; // negative while still prefilling
            switch (operation) {
                case Operation.SubmitLimit s -> {
                    Order order = s.order();
                    if (slot >= 0) {
                        record(slot, LIMIT, order.getId(), order.getPriceInPaise(),
                                order.getOriginalQuantity(), order.getSide(), order.getTimeInForce(),
                                order.getTrader()); // captured BEFORE the order is traded
                    }
                    scratch.processLimit(order);
                }
                case Operation.SubmitMarket s -> {
                    Order order = s.order();
                    if (slot >= 0) {
                        record(slot, MARKET, order.getId(), 0, order.getOriginalQuantity(),
                                order.getSide(), TimeInForce.IOC, order.getTrader());
                    }
                    scratch.processMarket(order);
                }
                case Operation.Cancel c -> {
                    if (slot >= 0) {
                        record(slot, CANCEL, c.orderId(), 0, 0, null, null, null);
                    }
                    scratch.processCancel(c.orderId());
                }
                case Operation.Modify m -> {
                    if (slot >= 0) {
                        record(slot, MODIFY, m.orderId(), m.newPrice(), m.newQuantity(), null, null, null);
                    }
                    scratch.processModify(m.orderId(), m.newPrice(), m.newQuantity());
                }
            }
            if (i == PREFILL - 1) {
                atPrefillEnd = scratch.snapshot();
            }
        }
        this.snapshot = atPrefillEnd;
    }

    private void record(int slot, int operationKind, long orderId, long orderPrice, long orderQuantity,
                        Side orderSide, TimeInForce orderTimeInForce, String orderTrader) {
        kind[slot] = operationKind;
        id[slot] = orderId;
        price[slot] = orderPrice;
        quantity[slot] = orderQuantity;
        side[slot] = orderSide;
        timeInForce[slot] = orderTimeInForce;
        trader[slot] = orderTrader;
    }

    /** A fresh engine in exactly the prefilled state. */
    MatchingEngine freshEngine() {
        return MatchingEngine.restore(snapshot);
    }

    /** Run measured operation {@code index} on {@code engine}; returns what happened. */
    List<Event> apply(MatchingEngine engine, int index) {
        switch (kind[index]) {
            case LIMIT:
                return engine.processLimit(Order.limit(id[index], trader[index], side[index],
                        price[index], quantity[index], engine.nextSequence(), timeInForce[index]));
            case MARKET:
                return engine.processMarket(Order.market(id[index], trader[index], side[index],
                        quantity[index], engine.nextSequence()));
            case CANCEL:
                return engine.processCancel(id[index]);
            default:
                return engine.processModify(id[index], price[index], quantity[index]);
        }
    }

    /** How many measured operations of each kind: {limit, market, cancel, modify}. */
    int[] mix() {
        int[] counts = new int[4];
        for (int k : kind) {
            counts[k]++;
        }
        return counts;
    }
}
