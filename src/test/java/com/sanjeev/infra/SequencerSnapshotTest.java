package com.sanjeev.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.engine.MatchingEngine;
import com.sanjeev.sim.CommandGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SequencerSnapshotTest {

    @TempDir
    Path dir;

    private List<String> snapshotFileNames(Path snapshotDir) throws IOException {
        try (Stream<Path> files = Files.list(snapshotDir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aSnapshotIsTakenAfterEveryNthCommandAndNotInBetween() throws Exception {
        Path snapshotDir = dir.resolve("snapshots");
        List<Command> commands = CommandGenerator.generate(11, 5_500);

        SequencerRun.run(dir.resolve("commands.log"), commands, new SnapshotStore(snapshotDir), 1_000);

        assertEquals(List.of("snapshot-1000.json", "snapshot-2000.json", "snapshot-3000.json",
                "snapshot-4000.json", "snapshot-5000.json"), snapshotFileNames(snapshotDir));
    }

    @Test
    void aSnapshotHoldsExactlyTheStateAfterItsOwnCommandAndNoLater() throws Exception {
        Path snapshotDir = dir.resolve("snapshots");
        List<Command> commands = CommandGenerator.generate(12, 3_500);
        SequencerRun.run(dir.resolve("commands.log"), commands, new SnapshotStore(snapshotDir), 1_000);

        for (int sequence : new int[] {1_000, 2_000, 3_000}) {
            SnapshotStore.RestoreResult restored = new SnapshotStore(snapshotDir).restoreLatest(sequence);

            assertEquals(sequence, restored.sequence());
            assertEquals(SequencerRun.stateAfter(commands, sequence).book().stateHash(),
                    restored.engine().book().stateHash(), "snapshot " + sequence);
        }
    }

    @Test
    void noSnapshotsAreTakenWhenNoStoreIsGiven() throws Exception {
        SequencerRun.run(dir.resolve("commands.log"), CommandGenerator.generate(13, 2_500), null, 1_000);

        assertEquals(List.of("commands.log"), snapshotFileNames(dir)); // only the log: no snapshot files
    }

    @Test
    void theDefaultIntervalIsTenThousandCommands() throws Exception {
        assertEquals(10_000, Sequencer.DEFAULT_SNAPSHOT_EVERY);

        Path snapshotDir = dir.resolve("snapshots");
        SnapshotStore store = new SnapshotStore(snapshotDir);
        MatchingEngine engine = new MatchingEngine();
        Sequencer sequencer = new Sequencer(engine, new EventLog(dir.resolve("commands.log")),
                (sequence, events) -> { }, 1024, store); // no interval given: the default applies
        sequencer.start();
        for (Command command : CommandGenerator.generate(14, 10_500)) {
            sequencer.submit(command);
        }
        sequencer.shutdown();

        assertEquals(List.of("snapshot-10000.json"), snapshotFileNames(snapshotDir));
    }

    @Test
    void aZeroOrNegativeIntervalIsRejected() throws Exception {
        SnapshotStore store = new SnapshotStore(dir.resolve("snapshots"));

        assertThrows(IllegalArgumentException.class, () -> new Sequencer(new MatchingEngine(),
                new EventLog(dir.resolve("a.log")), (s, e) -> { }, 16, store, 0));
        assertThrows(IllegalArgumentException.class, () -> new Sequencer(new MatchingEngine(),
                new EventLog(dir.resolve("b.log")), (s, e) -> { }, 16, store, -5));
    }

    @Test
    void aSnapshotThatCannotBeWrittenIsNotFatalAndEveryCommandIsStillLoggedAndApplied() throws Exception {
        Path snapshotDir = dir.resolve("snapshots");
        SnapshotStore store = new SnapshotStore(snapshotDir);
        Files.delete(snapshotDir); // from now on every snapshot write fails
        List<Command> commands = CommandGenerator.generate(15, 3_000);
        Path log = dir.resolve("commands.log");
        MatchingEngine engine = new MatchingEngine();
        Sequencer sequencer = new Sequencer(engine, new EventLog(log), (s, e) -> { }, 1024, store, 1_000);
        sequencer.start();

        for (Command command : commands) {
            sequencer.submit(command);
        }
        sequencer.shutdown();

        assertTrue(sequencer.failure().isEmpty(), "a failed snapshot must not stop the engine");
        assertTrue(sequencer.lastSnapshotFailure().isPresent());
        assertEquals(3_000, EventLog.readAll(log).size());
        assertEquals(SequencerRun.stateAfter(commands, 3_000).book().stateHash(),
                sequencer.engineAfterShutdown().book().stateHash());
    }

    @Test
    void whenAllSnapshotsSucceedThereIsNoSnapshotFailure() throws Exception {
        Sequencer sequencer = new Sequencer(new MatchingEngine(), new EventLog(dir.resolve("commands.log")),
                (s, e) -> { }, 1024, new SnapshotStore(dir.resolve("snapshots")), 500);
        sequencer.start();
        for (Command command : CommandGenerator.generate(16, 1_200)) {
            sequencer.submit(command);
        }
        sequencer.shutdown();

        assertFalse(sequencer.lastSnapshotFailure().isPresent());
    }
}
