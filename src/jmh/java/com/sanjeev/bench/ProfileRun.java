package com.sanjeev.bench;

import com.sanjeev.engine.Event;
import com.sanjeev.engine.MatchingEngine;
import java.util.List;

/**
 * Not a benchmark: runs the same workload in a plain loop for a while, so a profiler (Java
 * Flight Recorder) can look at it and say where the time and the allocations go. Run with:
 * mvn -Pjmh compile exec:exec@profile
 *
 * <p>Profilers tell you WHERE; JMH tells you HOW MUCH. This program says nothing about speed.
 */
public final class ProfileRun {

    private static final int BATCH = 10_000;

    /** Written every operation so the JIT cannot decide the results are unused. */
    private static volatile List<Event> sink;

    private ProfileRun() { }

    public static void main(String[] args) {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 25;
        Workload workload = new Workload(BATCH);
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        long operations = 0;
        while (System.nanoTime() < end) {
            MatchingEngine engine = workload.freshEngine();
            for (int i = 0; i < BATCH; i++) {
                sink = workload.apply(engine, i);
            }
            operations += BATCH;
        }
        System.out.printf("Profiled %,d operations over %d seconds%n", operations, seconds);
    }
}
