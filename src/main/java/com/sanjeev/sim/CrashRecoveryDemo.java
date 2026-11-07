package com.sanjeev.sim;

import com.sanjeev.infra.Command;
import com.sanjeev.infra.EventLog;
import com.sanjeev.infra.Recovery;
import com.sanjeev.infra.Sequencer;
import com.sanjeev.infra.SnapshotStore;
import com.sanjeev.engine.MatchingEngine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Demo: run an exchange, "crash" it halfway, recover from the files, and show that the
 * recovered book is identical to the one that was lost.
 *
 * <p>Run with {@code mvn compile exec:java}. Optional arguments: seed, commands before the
 * crash, snapshot interval (defaults 42, 50000, 8000).
 *
 * <p>The crash is simulated: the sequencer is stopped and the last log line is cut in half, which
 * is what the files look like if the power fails while a command is being written.
 */
public final class CrashRecoveryDemo {

    private CrashRecoveryDemo() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 42;
        int commandsBeforeCrash = args.length > 1 ? Integer.parseInt(args[1]) : 50_000;
        long snapshotEvery = args.length > 2 ? Long.parseLong(args[2]) : 8_000;

        Path dir = Files.createTempDirectory("matching-engine-demo");
        try {
            run(dir, seed, commandsBeforeCrash, snapshotEvery);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void run(Path dir, long seed, int commandsBeforeCrash, long snapshotEvery)
            throws Exception {
        Path logFile = dir.resolve("commands.log");
        SnapshotStore snapshots = new SnapshotStore(dir.resolve("snapshots"));

        System.out.printf("1. Running the exchange: seed %d, %,d commands, a snapshot every %,d%n",
                seed, commandsBeforeCrash, snapshotEvery);
        MatchingEngine liveEngine = new MatchingEngine();
        Sequencer sequencer = new Sequencer(liveEngine, new EventLog(logFile), (sequence, events) -> { },
                1024, snapshots, snapshotEvery);
        sequencer.start();
        for (Command command : CommandGenerator.generate(seed, commandsBeforeCrash)) {
            sequencer.submit(command);
        }
        sequencer.shutdown();
        if (sequencer.failure().isPresent()) {
            throw new IllegalStateException("the sequencer failed", sequencer.failure().get());
        }
        String hashBeforeCrash = sequencer.engineAfterShutdown().book().stateHash();
        int restingBeforeCrash = sequencer.engineAfterShutdown().book().restingOrderCount();
        System.out.printf("   resting orders: %,d%n   book hash before the crash: %s%n",
                restingBeforeCrash, hashBeforeCrash);

        System.out.println("2. CRASH: the power fails while command "
                + (commandsBeforeCrash + 1) + " is being written (the last log line is cut in half)");
        Files.writeString(logFile, "{\"sequence\":" + (commandsBeforeCrash + 1) + ",\"command\":{\"kind\":\"NewOr",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        System.out.println("3. Recovering from the files left behind");
        // Warm-up: the first pass through new code is slow while the JVM compiles it, which would
        // unfairly penalise whichever recovery runs first. Run both once, unmeasured.
        Recovery.replay(logFile);
        Recovery.recover(logFile, snapshots);

        long fullStart = System.nanoTime();
        Recovery.Result full = Recovery.replay(logFile);
        long fullNanos = System.nanoTime() - fullStart;

        long snapStart = System.nanoTime();
        Recovery.Result fast = Recovery.recover(logFile, snapshots);
        long snapNanos = System.nanoTime() - snapStart;

        System.out.printf("   full replay:        %,d commands replayed, %.1f ms, torn last line skipped: %b%n",
                full.commandsReplayed(), fullNanos / 1e6, full.tornTailDropped());
        System.out.printf("   snapshot + tail:    snapshot at %,d, %,d commands replayed, %.1f ms%n",
                fast.snapshotSequence(), fast.commandsReplayed(), snapNanos / 1e6);

        System.out.println("4. Comparing fingerprints");
        System.out.println("   before the crash:   " + hashBeforeCrash);
        System.out.println("   full replay:        " + full.stateHash());
        System.out.println("   snapshot + tail:    " + fast.stateHash());

        boolean match = hashBeforeCrash.equals(full.stateHash()) && hashBeforeCrash.equals(fast.stateHash());
        System.out.println(match ? "RESULT: MATCH - recovery rebuilt the exact same book"
                : "RESULT: MISMATCH - recovery is broken");
        if (!match) {
            System.exit(1);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> all = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : all) {
                Files.deleteIfExists(path);
            }
        }
    }
}
