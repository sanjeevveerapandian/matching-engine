package com.sanjeev.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The write-ahead log: every command is appended here, with its sequence number, as ONE line
 * of JSON, BEFORE the engine applies it. The file is therefore a complete, ordered record of
 * everything that was ever asked of the exchange.
 *
 * <p>Despite the name it stores COMMANDS (the inputs), not events: the engine is deterministic,
 * so the same commands in the same order always produce the same events and the same book.
 *
 * <p>Each append is flushed before it returns. Note that flush() hands the bytes to the
 * operating system; it does not force them onto the physical disk (that is fsync, which is
 * much slower). So a process crash loses nothing, but a power cut could still lose the last
 * few lines. That trade-off is deliberate here.
 *
 * <p>Not thread-safe: only the single engine thread appends.
 */
public final class EventLog implements AutoCloseable {

    // FAIL_ON_TRAILING_TOKENS: a line like {...}garbage is damage, not a valid entry
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Writer writer;

    /**
     * Open a NEW log file. Refuses to open an existing one: two runs must never mix their
     * sequence numbers (1, 2, 3 ... starting again) in one file.
     *
     * @throws java.nio.file.FileAlreadyExistsException if the file exists
     */
    public EventLog(Path file) throws IOException {
        this(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
    }

    /** For tests: log to any Writer. */
    EventLog(Writer writer) {
        this.writer = writer;
    }

    /** Append one command as one JSON line, then flush. */
    public void append(long sequence, Command command) throws IOException {
        String line = MAPPER.writeValueAsString(new LogEntry(sequence, command));
        writer.write(line + '\n');
        writer.flush();
    }

    /** Read a whole log back, in file order. Strict: any bad line is an error. */
    public static List<LogEntry> readAll(Path file) throws IOException {
        List<LogEntry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            entries.add(MAPPER.readValue(line, LogEntry.class));
        }
        return entries;
    }

    /**
     * What a recovery read found.
     *
     * @param entries         the valid commands, sequence numbers exactly 1, 2, 3, ...
     * @param tornTailDropped true if the last line was half-written or garbage and was skipped
     */
    public record ReadResult(List<LogEntry> entries, boolean tornTailDropped) { }

    /**
     * Read a log after a possible crash.
     *
     * <p>A crash can only damage the line that was being written, which is the LAST one. So:
     * <ul>
     *   <li>an unreadable LAST line is skipped and reported ({@code tornTailDropped}). That is
     *       safe: the command is written to the log before it is applied, so a command whose
     *       log line is incomplete was never applied;</li>
     *   <li>an unreadable line ANYWHERE ELSE, or sequence numbers that are not exactly
     *       1, 2, 3, ..., mean real corruption and throw {@link CorruptLogException}.</li>
     * </ul>
     * A last line that is complete JSON but only lacks its trailing newline is a complete
     * command and is kept.
     *
     * @throws CorruptLogException on damage that a crash cannot explain
     */
    public static ReadResult readRecoverable(Path file) throws IOException {
        LogScan scan = LogScan.of(file);
        return new ReadResult(scan.entriesAfter(0), scan.tornTailDropped());
    }

    /** One line to an entry, or null if it is not a valid entry. */
    static LogEntry parseOrNull(String line) {
        try {
            LogEntry entry = MAPPER.readValue(line, LogEntry.class);
            return entry != null && entry.command() != null ? entry : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
