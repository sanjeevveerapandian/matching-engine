package com.sanjeev.infra;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanjeev.engine.MatchingEngine;
import com.sanjeev.engine.OrderBook;
import com.sanjeev.sim.CommandGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotStoreTest {

    @TempDir
    Path dir;

    private final List<Command> commands = CommandGenerator.generate(7, 3_000);

    private Path snapshotDir() {
        return dir.resolve("snapshots");
    }

    private SnapshotStore store() throws IOException {
        return new SnapshotStore(snapshotDir());
    }

    private MatchingEngine engineAfter(int count) {
        return SequencerRun.stateAfter(commands, count);
    }

    private String hashAfter(int count) {
        return engineAfter(count).book().stateHash();
    }

    private Path file(long sequence) {
        return snapshotDir().resolve("snapshot-" + sequence + ".json");
    }

    // ---------------------------------------------------------------- the happy path

    @Test
    void aWrittenSnapshotComesBackWithTheSameFingerprintAndSequence() throws IOException {
        SnapshotStore store = store();
        store.write(1_000, engineAfter(1_000));

        SnapshotStore.RestoreResult result = store.restoreLatest(Long.MAX_VALUE);

        assertEquals(1_000, result.sequence());
        assertEquals(hashAfter(1_000), result.engine().book().stateHash());
        assertEquals(0, result.skipped());
    }

    @Test
    void theNewestSnapshotIsChosen() throws IOException {
        SnapshotStore store = store();
        store.write(1_000, engineAfter(1_000));
        store.write(2_000, engineAfter(2_000));
        store.write(3_000, engineAfter(3_000));

        SnapshotStore.RestoreResult result = store.restoreLatest(Long.MAX_VALUE);

        assertEquals(3_000, result.sequence());
        assertEquals(hashAfter(3_000), result.engine().book().stateHash());
    }

    @Test
    void snapshotsAheadOfTheLogAreSkippedBecauseTheLogIsTheTruth() throws IOException {
        SnapshotStore store = store();
        store.write(1_000, engineAfter(1_000));
        store.write(2_000, engineAfter(2_000));
        store.write(3_000, engineAfter(3_000));

        SnapshotStore.RestoreResult result = store.restoreLatest(2_500);

        assertEquals(2_000, result.sequence());
        assertEquals(1, result.skipped());
    }

    @Test
    void withNoSnapshotsTheResultIsABrandNewEngineAtSequenceZero() throws IOException {
        SnapshotStore.RestoreResult result = store().restoreLatest(Long.MAX_VALUE);

        assertEquals(0, result.sequence());
        assertEquals(new OrderBook().stateHash(), result.engine().book().stateHash());
        assertEquals(0, result.skipped());
    }

    @Test
    void aMissingSnapshotDirectoryMeansNoSnapshots() throws IOException {
        SnapshotStore store = store();
        Files.delete(snapshotDir());

        assertEquals(0, store.restoreLatest(Long.MAX_VALUE).sequence());
    }

    @Test
    void aSnapshotOfAnEmptyBookWorks() throws IOException {
        SnapshotStore store = store();
        store.write(0, new MatchingEngine());

        SnapshotStore.RestoreResult result = store.restoreLatest(Long.MAX_VALUE);

        assertEquals(0, result.engine().book().restingOrderCount());
    }

    // ---------------------------------------------------------------- a snapshot is never trusted blindly

    private SnapshotStore storeWithTwoSnapshots() throws IOException {
        SnapshotStore store = store();
        store.write(1_000, engineAfter(1_000));
        store.write(2_000, engineAfter(2_000));
        return store;
    }

    private void assertFallsBackToThe1000Snapshot(SnapshotStore store) throws IOException {
        SnapshotStore.RestoreResult result = store.restoreLatest(Long.MAX_VALUE);

        assertEquals(1_000, result.sequence());
        assertEquals(1, result.skipped());
        assertEquals(hashAfter(1_000), result.engine().book().stateHash());
    }

    @Test
    void aSnapshotFullOfGarbageIsSkipped() throws IOException {
        SnapshotStore store = storeWithTwoSnapshots();
        Files.writeString(file(2_000), "\u0000\u0001 not a snapshot", StandardCharsets.UTF_8);

        assertFallsBackToThe1000Snapshot(store);
    }

    @Test
    void aTruncatedSnapshotIsSkipped() throws IOException {
        SnapshotStore store = storeWithTwoSnapshots();
        String text = Files.readString(file(2_000));
        Files.writeString(file(2_000), text.substring(0, text.length() / 2));

        assertFallsBackToThe1000Snapshot(store);
    }

    @Test
    void aSnapshotWhoseStoredHashDoesNotMatchItsContentIsSkipped() throws IOException {
        SnapshotStore store = storeWithTwoSnapshots();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(file(2_000).toFile());
        root.put("stateHash", "0".repeat(64));
        mapper.writeValue(file(2_000).toFile(), root);

        assertFallsBackToThe1000Snapshot(store);
    }

    @Test
    void aSnapshotWhoseOrdersWereAlteredIsSkipped() throws IOException {
        SnapshotStore store = storeWithTwoSnapshots();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(file(2_000).toFile());
        ObjectNode firstOrder = (ObjectNode) root.get("engine").get("orders").get(0);
        firstOrder.put("remainingQuantity", firstOrder.get("remainingQuantity").asLong() - 1);
        mapper.writeValue(file(2_000).toFile(), root); // hash left as it was: it no longer matches

        assertFallsBackToThe1000Snapshot(store);
    }

    @Test
    void aSnapshotWithAnImpossibleOrderIsSkipped() throws IOException {
        SnapshotStore store = storeWithTwoSnapshots();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(file(2_000).toFile());
        ObjectNode firstOrder = (ObjectNode) root.get("engine").get("orders").get(0);
        firstOrder.put("remainingQuantity", firstOrder.get("originalQuantity").asLong() + 5);
        mapper.writeValue(file(2_000).toFile(), root);

        assertFallsBackToThe1000Snapshot(store);
    }

    @Test
    void aSnapshotWhoseFileNameDisagreesWithItsContentIsSkipped() throws IOException {
        SnapshotStore store = storeWithTwoSnapshots();
        Files.copy(file(1_000), file(5_000)); // content says 1000, name says 5000

        SnapshotStore.RestoreResult result = store.restoreLatest(Long.MAX_VALUE);

        assertEquals(2_000, result.sequence()); // 5000 skipped, 2000 is fine
        assertEquals(1, result.skipped());
    }

    @Test
    void ifEverySnapshotIsBadTheResultIsABrandNewEngine() throws IOException {
        SnapshotStore store = storeWithTwoSnapshots();
        Files.writeString(file(1_000), "garbage");
        Files.writeString(file(2_000), "garbage");

        SnapshotStore.RestoreResult result = store.restoreLatest(Long.MAX_VALUE);

        assertEquals(0, result.sequence());
        assertEquals(2, result.skipped());
        assertEquals(0, result.engine().book().restingOrderCount());
    }

    // ---------------------------------------------------------------- crash-safe writing

    @Test
    void aSuccessfulWriteLeavesNoTemporaryFileBehind() throws IOException {
        SnapshotStore store = store();
        store.write(1_000, engineAfter(1_000));

        try (Stream<Path> files = Files.list(snapshotDir())) {
            assertEquals(List.of("snapshot-1000.json"), files.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aStrayHalfWrittenTemporaryFileFromACrashIsIgnored() throws IOException {
        SnapshotStore store = store();
        store.write(1_000, engineAfter(1_000));
        Files.writeString(snapshotDir().resolve("snapshot-2000.tmp"), "{\"lastCommandSequence\":2000,\"sta");

        SnapshotStore.RestoreResult result = store.restoreLatest(Long.MAX_VALUE);

        assertEquals(1_000, result.sequence());
        assertEquals(0, result.skipped()); // not even counted: it is not a snapshot
    }

    @Test
    void unrelatedFilesInTheDirectoryAreIgnored() throws IOException {
        SnapshotStore store = store();
        store.write(1_000, engineAfter(1_000));
        Files.writeString(snapshotDir().resolve("notes.txt"), "hello");
        Files.writeString(snapshotDir().resolve("snapshot-abc.json"), "{}");

        assertEquals(1_000, store.restoreLatest(Long.MAX_VALUE).sequence());
    }

    @Test
    void writingTheSameSnapshotTwiceKeepsTheFirstOneUntouched() throws IOException {
        SnapshotStore store = store();
        store.write(1_000, engineAfter(1_000));
        byte[] first = Files.readAllBytes(file(1_000));

        store.write(1_000, engineAfter(2_000)); // a different state under the same name is ignored

        assertArrayEquals(first, Files.readAllBytes(file(1_000)));
        assertFalse(Files.exists(snapshotDir().resolve("snapshot-1000.tmp")));
    }

    @Test
    void aSnapshotFileIsJsonThatStatesItsSequenceAndFingerprint() throws IOException {
        SnapshotStore store = store();
        store.write(1_000, engineAfter(1_000));

        String json = Files.readString(file(1_000));

        assertTrue(json.contains("\"lastCommandSequence\":1000"));
        assertTrue(json.contains("\"stateHash\":\"" + hashAfter(1_000) + "\""));
    }
}
