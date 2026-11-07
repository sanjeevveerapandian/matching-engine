package com.sanjeev.infra;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A log file read for recovery, in two cheap stages so that a snapshot can really save work:
 *
 * <ol>
 *   <li>{@link #of}: split the file into lines, drop a torn last line, and parse ONLY the last
 *       line to learn the last sequence number. The rest is not parsed yet.</li>
 *   <li>{@link #entriesAfter}: parse and validate only the lines after the snapshot (plus the
 *       one line AT the snapshot, which only proves the log has a valid entry exactly there:
 *       it cannot prove the snapshot came from this log).</li>
 * </ol>
 *
 * <p>The log's sequence numbers are exactly 1, 2, 3, ..., so line number N holds sequence N.
 */
final class LogScan {

    private final String[] lines;          // valid lines only: a torn last line is not in here
    private final boolean tornTailDropped;
    private final long lastSequence;

    private LogScan(String[] lines, boolean tornTailDropped, long lastSequence) {
        this.lines = lines;
        this.tornTailDropped = tornTailDropped;
        this.lastSequence = lastSequence;
    }

    static LogScan of(Path file) throws IOException {
        // Decode leniently: a crash can cut a multi-byte character in half, and strict decoding
        // would throw before we could look at the line. Malformed bytes become U+FFFD instead.
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String[] all = text.split("\n", -1);
        int count = all.length;
        if (all[count - 1].isEmpty()) {
            count--; // the file ended with a newline: no partial line at the end
        }

        boolean torn = false;
        if (count > 0 && EventLog.parseOrNull(withoutCarriageReturn(all[count - 1])) == null) {
            torn = true; // an unreadable LAST line: what a crash while writing leaves behind
            count--;
        }

        String[] lines = new String[count];
        for (int i = 0; i < count; i++) {
            lines[i] = withoutCarriageReturn(all[i]);
        }

        long lastSequence = 0;
        if (count > 0) {
            LogEntry last = EventLog.parseOrNull(lines[count - 1]);
            if (last == null) {
                throw new CorruptLogException("line " + count + " is not a valid log entry and is not "
                        + "the last line: " + abbreviate(lines[count - 1]));
            }
            if (last.sequence() != count) {
                throw new CorruptLogException("the last line has sequence " + last.sequence()
                        + " but the log has " + count + " lines");
            }
            lastSequence = count;
        }
        return new LogScan(lines, torn, lastSequence);
    }

    boolean tornTailDropped() {
        return tornTailDropped;
    }

    /** The sequence number of the last valid command, or 0 for an empty log. */
    long lastSequence() {
        return lastSequence;
    }

    /**
     * The commands with a sequence number greater than {@code afterSequence}, validated: every
     * line parsed must be a real entry whose sequence equals its line number. Lines before the
     * snapshot are not parsed at all, except the one at {@code afterSequence} itself.
     *
     * @throws CorruptLogException on damage that a crash cannot explain
     */
    List<LogEntry> entriesAfter(long afterSequence) throws CorruptLogException {
        if (afterSequence < 0 || afterSequence > lastSequence) {
            throw new IllegalArgumentException("afterSequence " + afterSequence
                    + " is outside the log (last sequence " + lastSequence + ")");
        }
        List<LogEntry> entries = new ArrayList<>();
        for (int i = (int) Math.max(afterSequence - 1, 0); i < lines.length; i++) {
            LogEntry entry = EventLog.parseOrNull(lines[i]);
            if (entry == null) {
                throw new CorruptLogException("line " + (i + 1) + " is not a valid log entry and is not "
                        + "the last line: " + abbreviate(lines[i]));
            }
            if (entry.sequence() != i + 1L) {
                throw new CorruptLogException("line " + (i + 1) + " has sequence " + entry.sequence()
                        + " but " + (i + 1) + " was expected");
            }
            if (entry.sequence() > afterSequence) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static String withoutCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static String abbreviate(String line) {
        return line.length() <= 80 ? line : line.substring(0, 80) + "...";
    }
}
