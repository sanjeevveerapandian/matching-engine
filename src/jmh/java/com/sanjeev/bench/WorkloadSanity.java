package com.sanjeev.bench;

import com.sanjeev.engine.Event;
import com.sanjeev.engine.MatchingEngine;
import java.util.List;

/**
 * Not a benchmark: prints what the benchmark workload actually does, so the numbers can be
 * interpreted. Run with:  mvn -Pjmh compile exec:exec@sanity
 */
public final class WorkloadSanity {

    private WorkloadSanity() { }

    public static void main(String[] args) {
        int measured = 10_000;
        Workload workload = new Workload(measured);
        MatchingEngine engine = workload.freshEngine();

        int[] mix = workload.mix();
        System.out.printf("Seed %d, prefill %,d operations, then %,d measured operations%n",
                Workload.SEED, Workload.PREFILL, measured);
        System.out.printf("Resting orders at the start: %,d%n", workload.snapshot.orders().size());
        System.out.printf("Measured mix: limit %d, market %d, cancel %d, modify %d%n",
                mix[0], mix[1], mix[2], mix[3]);

        long accepted = 0;
        long rejected = 0;
        long trades = 0;
        long cancelled = 0;
        long modified = 0;
        for (int i = 0; i < measured; i++) {
            List<Event> events = workload.apply(engine, i);
            for (Event event : events) {
                switch (event) {
                    case Event.OrderAccepted e -> accepted++;
                    case Event.OrderRejected e -> rejected++;
                    case Event.TradeExecuted e -> trades++;
                    case Event.OrderCancelled e -> cancelled++;
                    case Event.OrderModified e -> modified++;
                }
            }
        }
        System.out.printf("Events: accepted %,d, rejected %,d, trades %,d, cancelled %,d, modified %,d%n",
                accepted, rejected, trades, cancelled, modified);
        System.out.printf("Trades per operation: %.2f%n", trades / (double) measured);
        System.out.printf("Resting orders at the end: %,d%n", engine.book().restingOrderCount());
    }
}
