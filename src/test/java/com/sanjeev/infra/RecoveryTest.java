package com.sanjeev.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.engine.BookInvariants;
import com.sanjeev.engine.Event;
import com.sanjeev.engine.MatchingEngine;
import com.sanjeev.engine.OrderBook;
import com.sanjeev.sim.CommandGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One real run of 50,000 random commands (through the sequencer, with a log file and snapshots
 * every 6,000) is shared by the tests. Tests that damage files work on COPIES.
 */
class RecoveryTest {

    private static final int TOTAL = 50_000;
    private static final long SNAPSHOT_EVERY = 6_000; // snapshots at 6000, 12000, ... 48000 (tail: 2000)
    private static final long LAST_SNAPSHOT = 48_000;

    @TempDir
    static Path shared;

    private static List<Command> commands;
    private static SequencerRun original;
    private static Path sharedLog;
    private static Path sharedSnapshots;
    private static String originalHash;

    @TempDir
    Path dir; // one fresh directory per test, for copies

    @BeforeAll
    static void runTheExchangeOnce() throws Exception {
        commands = CommandGenerator.generate(2024, TOTAL);
        sharedLog = shared.resolve("commands.log");
        sharedSnapshots = shared.resolve("snapshots");
        original = SequencerRun.run(sharedLog, commands, new SnapshotStore(sharedSnapshots), SNAPSHOT_EVERY);
        originalHash = original.engine.book().stateHash();
    }

    // ---------------------------------------------------------------- copies to damage safely

    private Path copyOfLog() throws IOException {
        Path copy = dir.resolve("commands.log");
        Files.copy(sharedLog, copy);
        return copy;
    }

    private SnapshotStore copyOfSnapshots() throws IOException {
        Path copy = dir.resolve("snapshots");
        Files.createDirectories(copy);
        try (Stream<Path> files = Files.list(sharedSnapshots)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.copy(file, copy.resolve(file.getFileName()));
            }
        }
        return new SnapshotStore(copy);
    }

    private static void appendText(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
    }

    // ---------------------------------------------------------------- the fixture is a real exchange

    @Test
    void theSharedRunIsNotTrivial() {
        assertTrue(original.engine.book().restingOrderCount() > 100);
        assertTrue(original.eventsAfter(0).stream().anyMatch(e -> e instanceof Event.TradeExecuted));
        assertTrue(original.eventsAfter(0).stream().anyMatch(e -> e instanceof Event.OrderRejected));
        assertEquals(TOTAL, original.eventsBySequence.size());
    }

    // ---------------------------------------------------------------- 1. full replay

    @Test
    void replayingTheLogOf50000RandomCommandsRebuildsTheSameBookAndTheSameEvents() throws IOException {
        List<Event> replayedEvents = new ArrayList<>();

        Recovery.Result result = Recovery.replay(sharedLog, (sequence, events) -> replayedEvents.addAll(events));

        assertEquals(originalHash, result.stateHash());
        assertEquals(TOTAL, result.lastSequence());
        assertEquals(TOTAL, result.commandsReplayed());
        assertEquals(0, result.snapshotSequence());
        assertFalse(result.tornTailDropped());
        assertEquals(original.eventsAfter(0), replayedEvents); // every accept, trade (with ids) and rejection
    }

    @Test
    void replayingTwiceGivesTheSameResultBecauseTheEngineIsDeterministic() throws IOException {
        List<Event> first = new ArrayList<>();
        List<Event> second = new ArrayList<>();

        Recovery.Result a = Recovery.replay(sharedLog, (s, e) -> first.addAll(e));
        Recovery.Result b = Recovery.replay(sharedLog, (s, e) -> second.addAll(e));

        assertEquals(a.stateHash(), b.stateHash());
        assertEquals(first, second);
    }

    @Test
    void aReplayedBookIsAlsoIdenticalWhenPrintedAsALadder() throws IOException {
        assertEquals(original.engine.book().toString(), Recovery.replay(sharedLog).engine().book().toString());
    }

    // ---------------------------------------------------------------- 2. snapshot + tail

    @Test
    void aSnapshotPlusTheTailGivesTheSameBookAsAFullReplay() throws IOException {
        List<Event> tailEvents = new ArrayList<>();

        Recovery.Result fast = Recovery.recover(sharedLog, new SnapshotStore(sharedSnapshots),
                (sequence, events) -> tailEvents.addAll(events));
        Recovery.Result full = Recovery.replay(sharedLog);

        assertEquals(LAST_SNAPSHOT, fast.snapshotSequence());
        assertEquals(TOTAL - LAST_SNAPSHOT, fast.commandsReplayed()); // only the tail
        assertEquals(full.stateHash(), fast.stateHash());
        assertEquals(originalHash, fast.stateHash());
        // the tail's events match the original run exactly, including trade ids: so the
        // snapshot restored the engine's counters, not only its orders
        assertEquals(original.eventsAfter(LAST_SNAPSHOT), tailEvents);
    }

    @Test
    void aSnapshotRecoveryReplaysFarFewerCommandsThanAFullReplay() throws IOException {
        Recovery.Result fast = Recovery.recover(sharedLog, new SnapshotStore(sharedSnapshots));
        Recovery.Result full = Recovery.replay(sharedLog);

        assertTrue(fast.commandsReplayed() * 10 < full.commandsReplayed());
    }

    @Test
    void recoveringWithNoSnapshotsAtAllBehavesLikeAFullReplay() throws IOException {
        SnapshotStore empty = new SnapshotStore(dir.resolve("none"));

        Recovery.Result result = Recovery.recover(sharedLog, empty);

        assertEquals(0, result.snapshotSequence());
        assertEquals(TOTAL, result.commandsReplayed());
        assertEquals(originalHash, result.stateHash());
    }

    @Test
    void aSnapshotTakenAtTheVeryLastCommandNeedsNoReplayAtAll() throws Exception {
        List<Command> small = CommandGenerator.generate(3, 3_000);
        Path log = dir.resolve("small.log");
        SnapshotStore store = new SnapshotStore(dir.resolve("small-snapshots"));
        SequencerRun run = SequencerRun.run(log, small, store, 1_000); // snapshots at 1000, 2000, 3000

        Recovery.Result result = Recovery.recover(log, store);

        assertEquals(3_000, result.snapshotSequence());
        assertEquals(0, result.commandsReplayed());
        assertEquals(run.engine.book().stateHash(), result.stateHash());
    }

    @Test
    void aSnapshotRecoveredEngineAndAFullyReplayedEngineKeepBehavingIdentically() throws IOException {
        Recovery.Result fast = Recovery.recover(sharedLog, new SnapshotStore(sharedSnapshots));
        Recovery.Result full = Recovery.replay(sharedLog);
        CommandProcessor fastProcessor = new CommandProcessor(fast.engine());
        CommandProcessor fullProcessor = new CommandProcessor(full.engine());
        List<Command> more = CommandGenerator.generate(555, 2_000);

        for (int i = 0; i < more.size(); i++) {
            long sequence = TOTAL + 1L + i;
            assertEquals(fullProcessor.apply(sequence, more.get(i)), fastProcessor.apply(sequence, more.get(i)),
                    "the two engines diverged at command " + sequence);
        }
        assertEquals(full.engine().book().stateHash(), fast.engine().book().stateHash());
    }

    // ---------------------------------------------------------------- 3. a crash while writing the last line

    @Test
    void aHalfWrittenLastLineIsSkippedAndTheStateIsTheLastFullyLoggedOne() throws IOException {
        Path log = copyOfLog();
        appendText(log, "{\"sequence\":" + (TOTAL + 1) + ",\"command\":{\"kind\":\"NewOrder\",\"orderId\":99");

        Recovery.Result full = Recovery.replay(log);
        Recovery.Result fast = Recovery.recover(log, copyOfSnapshots());

        for (Recovery.Result result : List.of(full, fast)) {
            assertTrue(result.tornTailDropped());
            assertEquals(TOTAL, result.lastSequence());
            assertEquals(originalHash, result.stateHash());
        }
    }

    @Test
    void aRealLastCommandCutInHalfIsDroppedSoTheStateIsThatOfTheCommandsBeforeIt() throws IOException {
        Path log = copyOfLog();
        String text = Files.readString(log, StandardCharsets.UTF_8);
        int startOfLastLine = text.lastIndexOf('\n', text.length() - 2) + 1;
        Files.writeString(log, text.substring(0, startOfLastLine + (text.length() - startOfLastLine) / 2),
                StandardCharsets.UTF_8);

        Recovery.Result result = Recovery.recover(log, copyOfSnapshots());

        assertTrue(result.tornTailDropped());
        assertEquals(TOTAL - 1, result.lastSequence());
        // worked out independently, with no files: apply the first TOTAL-1 commands to a new engine
        assertEquals(SequencerRun.stateAfter(commands, TOTAL - 1).book().stateHash(), result.stateHash());
    }

    // ---------------------------------------------------------------- damaged snapshots and logs

    @Test
    void ifTheNewestSnapshotIsCorruptRecoveryFallsBackToAnOlderOneAndStillMatches() throws IOException {
        SnapshotStore store = copyOfSnapshots();
        Files.writeString(dir.resolve("snapshots").resolve("snapshot-" + LAST_SNAPSHOT + ".json"), "garbage");

        Recovery.Result result = Recovery.recover(sharedLog, store);

        assertEquals(LAST_SNAPSHOT - SNAPSHOT_EVERY, result.snapshotSequence());
        assertEquals(1, result.snapshotsSkipped());
        assertEquals(originalHash, result.stateHash());
    }

    @Test
    void ifEverySnapshotIsCorruptRecoveryFallsBackToAFullReplay() throws IOException {
        SnapshotStore store = copyOfSnapshots();
        try (Stream<Path> files = Files.list(dir.resolve("snapshots"))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Files.writeString(file, "garbage");
            }
        }

        Recovery.Result result = Recovery.recover(sharedLog, store);

        assertEquals(0, result.snapshotSequence());
        assertEquals(TOTAL, result.commandsReplayed());
        assertEquals(8, result.snapshotsSkipped());
        assertEquals(originalHash, result.stateHash());
    }

    @Test
    void aSnapshotAheadOfAShortenedLogIsNotUsedBecauseTheLogIsTheTruth() throws IOException {
        Path log = dir.resolve("shortened.log");
        List<String> lines = Files.readAllLines(sharedLog, StandardCharsets.UTF_8);
        Files.write(log, lines.subList(0, 45_000), StandardCharsets.UTF_8); // snapshot 48000 is now in the future

        Recovery.Result result = Recovery.recover(log, copyOfSnapshots());

        assertEquals(45_000, result.lastSequence());
        assertEquals(42_000, result.snapshotSequence());
        assertEquals(1, result.snapshotsSkipped());
        assertEquals(3_000, result.commandsReplayed());
        assertEquals(SequencerRun.stateAfter(commands, 45_000).book().stateHash(), result.stateHash());
    }

    @Test
    void damageInTheMiddleOfTheLogStopsAFullReplay() throws IOException {
        Path log = dir.resolve("damaged.log");
        List<String> lines = Files.readAllLines(sharedLog, StandardCharsets.UTF_8);
        lines.set(20_000, "THIS LINE WAS DAMAGED");
        Files.write(log, lines, StandardCharsets.UTF_8);

        assertThrows(CorruptLogException.class, () -> Recovery.replay(log));
    }

    @Test
    void damageAfterTheSnapshotStopsSnapshotRecoveryToo() throws IOException {
        Path log = dir.resolve("damaged-tail.log");
        List<String> lines = Files.readAllLines(sharedLog, StandardCharsets.UTF_8);
        lines.set(49_000, "THIS LINE WAS DAMAGED");
        Files.write(log, lines, StandardCharsets.UTF_8);

        assertThrows(CorruptLogException.class, () -> Recovery.recover(log, copyOfSnapshots()));
    }

    @Test
    void damageBeforeTheSnapshotIsNotReadBySnapshotRecoveryButIsFoundByAFullReplay() throws IOException {
        Path log = dir.resolve("damaged-old.log");
        List<String> lines = Files.readAllLines(sharedLog, StandardCharsets.UTF_8);
        lines.set(1_000, "THIS OLD LINE WAS DAMAGED"); // long before snapshot 48000 supersedes it
        Files.write(log, lines, StandardCharsets.UTF_8);

        Recovery.Result fast = Recovery.recover(log, copyOfSnapshots());

        assertEquals(originalHash, fast.stateHash()); // the snapshot made those old lines irrelevant
        assertThrows(CorruptLogException.class, () -> Recovery.replay(log));
    }

    // ---------------------------------------------------------------- edges

    @Test
    void aMissingLogFileIsReported() {
        assertThrows(NoSuchFileException.class, () -> Recovery.replay(dir.resolve("nope.log")));
    }

    @Test
    void anEmptyLogRecoversToABrandNewEngine() throws IOException {
        Path log = dir.resolve("empty.log");
        Files.writeString(log, "");

        Recovery.Result result = Recovery.replay(log);

        assertEquals(0, result.lastSequence());
        assertEquals(0, result.commandsReplayed());
        assertEquals(new OrderBook().stateHash(), result.stateHash());
    }

    @Test
    void aRecoveredEngineAlwaysPassesTheBookInvariantCheck() throws IOException {
        MatchingEngine engine = Recovery.recover(sharedLog, new SnapshotStore(sharedSnapshots)).engine();

        BookInvariants.check(engine.book());
    }
}
