package com.sanjeev.bench;

import com.sanjeev.engine.MatchingEngine;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * How many operations per second can the pure engine process?
 *
 * <p>One benchmark invocation = one batch of {@value #BATCH} operations from the seeded mix, run
 * on a fresh engine that starts from the same pre-filled book. {@code @OperationsPerInvocation}
 * makes JMH report the score per OPERATION, not per batch.
 *
 * <p>Warm-up and forks: see BENCHMARKS.md.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class ThroughputBenchmark {

    static final int BATCH = 10_000;

    private Workload workload;
    private MatchingEngine engine;

    @Setup(Level.Trial)
    public void buildWorkload() {
        workload = new Workload(BATCH);
    }

    /** Before every batch (not timed): a fresh engine holding the same pre-filled book. */
    @Setup(Level.Invocation)
    public void resetEngine() {
        engine = workload.freshEngine();
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void processTheMix(Blackhole blackhole) {
        for (int i = 0; i < BATCH; i++) {
            blackhole.consume(workload.apply(engine, i)); // the Blackhole stops the JIT discarding the work
        }
    }
}
