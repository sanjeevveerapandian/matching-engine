package com.sanjeev.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanjeev.engine.BookInvariants;
import com.sanjeev.engine.MatchingEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Snapshots of the engine, one file per snapshot: {@code snapshot-<last command sequence>.json}
 * in one directory.
 *
 * <p>Writing is crash-safe: the JSON goes to a {@code .tmp} file, is forced to disk, and is then
 * moved into place with one atomic rename. So a snapshot file is either complete or does not
 * exist; a crash mid-write leaves at worst a stray {@code .tmp}, which loading ignores.
 *
 * <p>Loading never trusts a file: it checks the sequence number against the file name, rebuilds
 * the engine, runs {@link BookInvariants}, and compares the book's hash with the one stored in
 * the file. Any snapshot that fails is skipped, and the next older one is tried.
 */
public final class SnapshotStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern FILE_NAME = Pattern.compile("snapshot-(\\d+)\\.json");

    private final Path directory;

    /** Uses {@code directory}, creating it if needed. */
    public SnapshotStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        this.directory = directory;
    }

    /**
     * What loading found.
     *
     * @param sequence the last command already contained in {@code engine}; 0 means no snapshot
     *                 was usable and {@code engine} is brand new
     * @param skipped  snapshots passed over because they were corrupt or ahead of the log
     */
    public record RestoreResult(long sequence, MatchingEngine engine, int skipped) { }

    /**
     * Save the engine as of {@code lastCommandSequence}. Does nothing if that snapshot already
     * exists. Call from the thread that runs the engine, right after applying that command.
     */
    public void write(long lastCommandSequence, MatchingEngine engine) throws IOException {
        Path destination = pathFor(lastCommandSequence);
        if (Files.exists(destination)) {
            return;
        }
        SnapshotFile file = new SnapshotFile(lastCommandSequence, engine.book().stateHash(), engine.snapshot());
        byte[] json = MAPPER.writeValueAsBytes(file);

        Path temp = directory.resolve("snapshot-" + lastCommandSequence + ".tmp");
        Files.write(temp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, StandardOpenOption.SYNC); // SYNC: on the disk before we rename
        Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Load the newest usable snapshot whose sequence is at most {@code maxSequence} (the last
     * sequence found in the log: a snapshot AHEAD of the log cannot be proven consistent with
     * it, and the log is the source of truth). Falls back to older snapshots, and finally to a
     * brand-new engine.
     */
    public RestoreResult restoreLatest(long maxSequence) throws IOException {
        int skipped = 0;
        for (long sequence : snapshotSequencesNewestFirst()) {
            if (sequence > maxSequence) {
                skipped++;
                continue;
            }
            try {
                return new RestoreResult(sequence, load(sequence), skipped);
            } catch (IOException | RuntimeException e) {
                skipped++; // corrupt or inconsistent: try the next older one
            }
        }
        return new RestoreResult(0, new MatchingEngine(), skipped);
    }

    private MatchingEngine load(long sequence) throws IOException {
        SnapshotFile file = MAPPER.readValue(Files.readAllBytes(pathFor(sequence)), SnapshotFile.class);
        if (file.lastCommandSequence() != sequence) {
            throw new IOException("snapshot file name says " + sequence + " but it contains "
                    + file.lastCommandSequence());
        }
        MatchingEngine engine = MatchingEngine.restore(file.engine());
        BookInvariants.check(engine.book());
        if (!engine.book().stateHash().equals(file.stateHash())) {
            throw new IOException("snapshot " + sequence + " does not match its own stored hash");
        }
        return engine;
    }

    private List<Long> snapshotSequencesNewestFirst() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Long> sequences = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Matcher matcher = FILE_NAME.matcher(file.getFileName().toString());
                if (matcher.matches()) {
                    sequences.add(Long.parseLong(matcher.group(1)));
                }
            }
        }
        sequences.sort(Comparator.reverseOrder());
        return sequences;
    }

    private Path pathFor(long sequence) {
        return directory.resolve("snapshot-" + sequence + ".json");
    }
}
