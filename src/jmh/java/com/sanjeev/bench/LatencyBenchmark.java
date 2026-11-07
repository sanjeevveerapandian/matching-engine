package com.sanjeev.bench;

import com.sanjeev.engine.Event;
import com.sanjeev.engine.MatchingEngine;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * How long does ONE operation take, and how bad is the tail?
 *
 * <p>Each invocation processes exactly one operation, and JMH's SampleTime mode times every one
 * (or a uniform sample of them) individually, then reports the average and percentiles:
 * p50 is the typical operation, p99 is "1 in 100 is slower than this", p99.9 is "1 in 1000".
 *
 * <p>The engine is reset to the pre-filled book every {@value #OPERATIONS} operations. The reset
 * happens in the untimed per-invocation setup, so it never shows up as a latency sample.
 *
 * <p>Caveat: JMH reads {@code System.nanoTime()} around each call. On Windows that clock ticks in
 * about 100 ns steps, so sub-microsecond latencies are coarse: read the numbers as "roughly",
 * not to the nanosecond.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class LatencyBenchmark {

    static final int OPERATIONS = 50_000;

    private Workload workload;
    private MatchingEngine engine;
    private int cursor;

    @Setup(Level.Trial)
    public void buildWorkload() {
        workload = new Workload(OPERATIONS);
    }

    /** Untimed. Starts over from the pre-filled book when the measured operations run out. */
    @Setup(Level.Invocation)
    public void prepare() {
        if (engine == null || cursor == OPERATIONS) {
            engine = workload.freshEngine();
            cursor = 0;
        }
    }

    @Benchmark
    public List<Event> processOneOperation() {
        return workload.apply(engine, cursor++);
    }
}
