package com.sanjeev.infra;

import com.sanjeev.engine.BookInvariants;
import com.sanjeev.engine.MatchingEngine;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Rebuilds an engine from what is on disk after a crash.
 *
 * <p>The engine is deterministic: it reads no clock and no randomness, and orders are numbered
 * by the commands' sequence numbers. So the same commands applied in the same order from the
 * same starting state always give the same book, the same trades and the same events.
 *
 * <ul>
 *   <li>{@link #replay}: start from an empty engine and re-apply EVERY command in the log.</li>
 *   <li>{@link #recover}: start from the newest usable snapshot and re-apply only the commands
 *       AFTER it. The same result, far less work.</li>
 * </ul>
 *
 * <p>Both finish by checking the book's invariants, so a recovery that produced a corrupt
 * book fails loudly instead of quietly serving bad data.
 *
 * <p>Not covered here: continuing to accept new commands after recovery. That needs the log's
 * torn tail truncated first, and the sequencer to resume numbering from {@code lastSequence + 1}.
 */
public final class Recovery {

    /**
     * @param engine           the rebuilt engine
     * @param lastSequence     the sequence number of the last command in the log
     * @param snapshotSequence the snapshot recovery started from (0 = started from nothing)
     * @param commandsReplayed how many log commands were re-applied
     * @param tornTailDropped  true if a half-written last log line was found and skipped
     * @param snapshotsSkipped snapshots passed over (corrupt, or ahead of the log)
     */
    public record Result(
            MatchingEngine engine,
            long lastSequence,
            long snapshotSequence,
            int commandsReplayed,
            boolean tornTailDropped,
            int snapshotsSkipped) {

        /** The recovered book's fingerprint. */
        public String stateHash() {
            return engine.book().stateHash();
        }
    }

    private Recovery() { }

    /** Rebuild by replaying the whole log into a fresh engine. */
    public static Result replay(Path logFile) throws IOException {
        return replay(logFile, (sequence, events) -> { });
    }

    /** Like {@link #replay(Path)}, and hands each command's events to {@code events} as it goes. */
    public static Result replay(Path logFile, EventPublisher events) throws IOException {
        return rebuild(logFile, null, events);
    }

    /** Rebuild from the newest usable snapshot plus the log commands after it. */
    public static Result recover(Path logFile, SnapshotStore snapshots) throws IOException {
        return recover(logFile, snapshots, (sequence, events) -> { });
    }

    /** Like {@link #recover(Path, SnapshotStore)}, and publishes the events of the replayed tail. */
    public static Result recover(Path logFile, SnapshotStore snapshots, EventPublisher events)
            throws IOException {
        return rebuild(logFile, snapshots, events);
    }

    private static Result rebuild(Path logFile, SnapshotStore snapshots, EventPublisher events)
            throws IOException {
        LogScan scan = LogScan.of(logFile); // cheap: only the last line is parsed

        SnapshotStore.RestoreResult start = snapshots == null
                ? new SnapshotStore.RestoreResult(0, new MatchingEngine(), 0)
                : snapshots.restoreLatest(scan.lastSequence());

        MatchingEngine engine = start.engine();
        CommandProcessor processor = new CommandProcessor(engine);
        int replayed = 0;
        // parses only the lines after the snapshot: the old part of the log is not even decoded
        for (LogEntry entry : scan.entriesAfter(start.sequence())) {
            events.publish(entry.sequence(), processor.apply(entry.sequence(), entry.command()));
            replayed++;
        }

        BookInvariants.check(engine.book());
        return new Result(engine, scan.lastSequence(), start.sequence(), replayed,
                scan.tornTailDropped(), start.skipped());
    }
}
