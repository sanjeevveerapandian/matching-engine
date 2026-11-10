package com.sanjeev.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How the log reader behaves after a crash: what it forgives, and what it refuses to guess about. */
class LogRecoveryReadTest {

    @TempDir
    Path dir;

    private Path logFile() {
        return dir.resolve("commands.log");
    }

    /** A valid log of {@code count} commands, sequence numbers 1..count. */
    private Path writeLog(int count) throws IOException {
        Path file = logFile();
        try (EventLog log = new EventLog(file)) {
            for (int i = 1; i <= count; i++) {
                log.append(i, Command.NewOrder.limit(i, "Ravi", Side.SELL, 3000 + i, 10, TimeInForce.GTC));
            }
        }
        return file;
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static void write(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static List<Long> oneTo(int n) {
        return LongStream.rangeClosed(1, n).boxed().toList();
    }

    private static List<Long> sequencesOf(EventLog.ReadResult result) {
        return result.entries().stream().map(LogEntry::sequence).toList();
    }

    // ---------------------------------------------------------------- a clean log

    @Test
    void aCleanLogIsReadCompletelyWithNothingDropped() throws IOException {
        Path file = writeLog(20);

        EventLog.ReadResult result = EventLog.readRecoverable(file);

        assertEquals(oneTo(20), sequencesOf(result));
        assertFalse(result.tornTailDropped());
    }

    @Test
    void anEmptyLogHasNoEntriesAndNothingDropped() throws IOException {
        write(logFile(), "");

        EventLog.ReadResult result = EventLog.readRecoverable(logFile());

        assertTrue(result.entries().isEmpty());
        assertFalse(result.tornTailDropped());
    }

    @Test
    void windowsLineEndingsAreAccepted() throws IOException {
        Path file = writeLog(5);
        write(file, read(file).replace("\n", "\r\n"));

        assertEquals(oneTo(5), sequencesOf(EventLog.readRecoverable(file)));
    }

    // ---------------------------------------------------------------- a crash while writing the LAST line

    @Test
    void aHalfWrittenLastLineIsDroppedAndEverythingBeforeItIsKept() throws IOException {
        Path file = writeLog(10);
        write(file, read(file) + "{\"sequence\":11,\"command\":{\"kind\":\"NewOr");

        EventLog.ReadResult result = EventLog.readRecoverable(file);

        assertEquals(oneTo(10), sequencesOf(result));
        assertTrue(result.tornTailDropped());
    }

    @Test
    void aRealLastLineCutInHalfIsDropped() throws IOException {
        Path file = writeLog(10);
        String text = read(file);
        int startOfLastLine = text.lastIndexOf('\n', text.length() - 2) + 1;
        int cutPoint = startOfLastLine + (text.length() - startOfLastLine) / 2;
        write(file, text.substring(0, cutPoint));

        EventLog.ReadResult result = EventLog.readRecoverable(file);

        assertEquals(oneTo(9), sequencesOf(result));
        assertTrue(result.tornTailDropped());
    }

    @Test
    void aLastLineOfPureGarbageIsDropped() throws IOException {
        Path file = writeLog(3);
        write(file, read(file) + "\u0000\u0000\u0000 not json at all");

        EventLog.ReadResult result = EventLog.readRecoverable(file);

        assertEquals(oneTo(3), sequencesOf(result));
        assertTrue(result.tornTailDropped());
    }

    @Test
    void aValidLineFollowedByJunkOnTheSameLineIsTreatedAsDamage() throws IOException {
        Path file = writeLog(3);
        write(file, read(file) + "{\"sequence\":4,\"command\":{\"kind\":\"CancelOrder\",\"orderId\":1}}garbage");

        EventLog.ReadResult result = EventLog.readRecoverable(file);

        assertEquals(oneTo(3), sequencesOf(result));
        assertTrue(result.tornTailDropped());
    }

    @Test
    void aLastLineThatIsCompleteJsonButLacksOnlyItsNewlineIsAValidCommandAndIsKept() throws IOException {
        Path file = writeLog(5);
        String text = read(file);
        write(file, text.substring(0, text.length() - 1)); // remove only the final '\n'

        EventLog.ReadResult result = EventLog.readRecoverable(file);

        assertEquals(oneTo(5), sequencesOf(result));
        assertFalse(result.tornTailDropped());
    }

    @Test
    void aCrashThatCutsAMultiByteCharacterInHalfIsStillRecognisedAsATornLine() throws IOException {
        Path file = writeLog(2);
        Path scratch = dir.resolve("scratch.log");
        try (EventLog log = new EventLog(scratch)) {
            log.append(3, Command.NewOrder.limit(3, "Ravī", Side.SELL, 3000, 10, TimeInForce.GTC));
        }
        byte[] line = Files.readAllBytes(scratch);
        int leadByte = 0;
        while ((line[leadByte] & 0xFF) < 0xC0) { // the first byte of the two-byte character
            leadByte++;
        }
        byte[] valid = Files.readAllBytes(file);
        byte[] cut = new byte[valid.length + leadByte + 1];
        System.arraycopy(valid, 0, cut, 0, valid.length);
        System.arraycopy(line, 0, cut, valid.length, leadByte + 1); // ends in half a character
        Files.write(file, cut);

        EventLog.ReadResult result = EventLog.readRecoverable(file);

        assertEquals(oneTo(2), sequencesOf(result));
        assertTrue(result.tornTailDropped());
    }

    @Test
    void aLogThatOnlyContainsATornLineHasNoEntries() throws IOException {
        write(logFile(), "{\"sequence\":1,\"comm");

        EventLog.ReadResult result = EventLog.readRecoverable(logFile());

        assertTrue(result.entries().isEmpty());
        assertTrue(result.tornTailDropped());
    }

    // ---------------------------------------------------------------- damage a crash cannot explain

    @Test
    void anUnreadableLineInTheMiddleIsCorruptionNotACrashAndNamesTheLine() throws IOException {
        Path file = writeLog(6);
        List<String> lines = Files.readAllLines(file);
        lines.set(2, "{\"sequence\":3,\"command\":{\"kind\":\"NewOr");
        Files.write(file, lines);

        CorruptLogException e = assertThrows(CorruptLogException.class, () -> EventLog.readRecoverable(file));

        assertTrue(e.getMessage().contains("line 3"), e.getMessage());
    }

    @Test
    void aMissingSequenceNumberInTheMiddleIsCorruption() throws IOException {
        Path file = writeLog(6);
        List<String> lines = Files.readAllLines(file);
        lines.remove(2); // sequence 3 vanishes
        Files.write(file, lines);

        assertThrows(CorruptLogException.class, () -> EventLog.readRecoverable(file));
    }

    @Test
    void aRepeatedLineIsCorruption() throws IOException {
        Path file = writeLog(6);
        List<String> lines = Files.readAllLines(file);
        lines.add(3, lines.get(2)); // sequence 3 appears twice
        Files.write(file, lines);

        assertThrows(CorruptLogException.class, () -> EventLog.readRecoverable(file));
    }

    @Test
    void aBlankLineInTheMiddleIsCorruption() throws IOException {
        Path file = writeLog(4);
        List<String> lines = Files.readAllLines(file);
        lines.add(2, "");
        Files.write(file, lines);

        assertThrows(CorruptLogException.class, () -> EventLog.readRecoverable(file));
    }

    @Test
    void twoDamagedLinesAtTheEndAreCorruptionBecauseOnlyTheVeryLastCanBeTorn() throws IOException {
        Path file = writeLog(5);
        List<String> lines = Files.readAllLines(file);
        lines.set(3, "{\"sequence\":4,\"co");
        lines.set(4, "{\"sequence\":5,\"co");
        Files.write(file, lines);

        assertThrows(CorruptLogException.class, () -> EventLog.readRecoverable(file));
    }

    @Test
    void aLastLineWhoseSequenceDoesNotFollowOnIsCorruption() throws IOException {
        Path file = writeLog(5);
        write(file, read(file) + "{\"sequence\":9,\"command\":{\"kind\":\"CancelOrder\",\"orderId\":1}}\n");

        assertThrows(CorruptLogException.class, () -> EventLog.readRecoverable(file));
    }

    @Test
    void twoSwappedSequenceNumbersInTheMiddleAreCorruptionEvenThoughTheLastLineLooksRight() throws IOException {
        Path file = writeLog(5);
        List<String> lines = Files.readAllLines(file);
        lines.set(1, lines.get(1).replace("\"sequence\":2", "\"sequence\":3")); // line 2 claims to be 3
        lines.set(2, lines.get(2).replace("\"sequence\":3", "\"sequence\":2")); // line 3 claims to be 2
        Files.write(file, lines);

        CorruptLogException e = assertThrows(CorruptLogException.class, () -> EventLog.readRecoverable(file));

        assertTrue(e.getMessage().contains("line 2") && e.getMessage().contains("expected"), e.getMessage());
    }

    @Test
    void aLongDamagedLineIsShortenedInTheErrorMessageSoTheLogStaysReadable() throws IOException {
        Path file = writeLog(4);
        List<String> lines = Files.readAllLines(file);
        lines.set(1, "x".repeat(500));
        Files.write(file, lines);

        CorruptLogException e = assertThrows(CorruptLogException.class, () -> EventLog.readRecoverable(file));

        assertTrue(e.getMessage().contains("x".repeat(80) + "..."), e.getMessage());
        assertTrue(e.getMessage().length() < 300, "message was " + e.getMessage().length() + " characters");
    }

    @Test
    void aLineThatIsValidJsonButHasNoCommandInTheMiddleIsCorruption() throws IOException {
        Path file = writeLog(4);
        List<String> lines = Files.readAllLines(file);
        lines.set(1, "{\"sequence\":2}"); // an entry with nothing to replay
        Files.write(file, lines);

        assertThrows(CorruptLogException.class, () -> EventLog.readRecoverable(file));
    }

    @Test
    void theWordNullInTheMiddleIsCorruptionNotAnEmptyEntry() throws IOException {
        Path file = writeLog(4);
        List<String> lines = Files.readAllLines(file);
        lines.set(1, "null"); // valid JSON, but it is not an entry
        Files.write(file, lines);

        assertThrows(CorruptLogException.class, () -> EventLog.readRecoverable(file));
    }

    // ---------------------------------------------------------------- the cheap two-stage read (LogScan)

    @Test
    void theScanKnowsTheLastSequenceWithoutParsingTheWholeLog() throws IOException {
        Path file = writeLog(50);
        List<String> lines = Files.readAllLines(file);
        lines.set(10, "THIS EARLY LINE IS DAMAGED"); // the scan must not even look at it
        Files.write(file, lines);

        LogScan scan = LogScan.of(file);

        assertEquals(50, scan.lastSequence());
        assertFalse(scan.tornTailDropped());
    }

    @Test
    void entriesAfterASnapshotAreReadWithoutParsingTheLinesBeforeIt() throws IOException {
        Path file = writeLog(50);
        List<String> lines = Files.readAllLines(file);
        lines.set(10, "THIS EARLY LINE IS DAMAGED");
        Files.write(file, lines);
        LogScan scan = LogScan.of(file);

        List<LogEntry> tail = scan.entriesAfter(40);

        assertEquals(oneTo(50).subList(40, 50), tail.stream().map(LogEntry::sequence).toList());
        assertThrows(CorruptLogException.class, () -> scan.entriesAfter(0)); // reading it all does see it
    }

    @Test
    void entriesAfterReturnsNothingWhenTheSnapshotIsTheLastCommand() throws IOException {
        LogScan scan = LogScan.of(writeLog(10));

        assertTrue(scan.entriesAfter(10).isEmpty());
    }

    @Test
    void theLineAtTheSnapshotPositionMustBeValid() throws IOException {
        Path file = writeLog(20);
        List<String> lines = Files.readAllLines(file);
        lines.set(9, "DAMAGED"); // line 10, exactly where the snapshot says it is
        Files.write(file, lines);
        LogScan scan = LogScan.of(file);

        assertThrows(CorruptLogException.class, () -> scan.entriesAfter(10));
    }

    @Test
    void askingForEntriesAfterASequenceBeyondTheLogIsRejected() throws IOException {
        LogScan scan = LogScan.of(writeLog(10));

        assertThrows(IllegalArgumentException.class, () -> scan.entriesAfter(11));
        assertThrows(IllegalArgumentException.class, () -> scan.entriesAfter(-1));
    }

    @Test
    void aTornTailIsExcludedFromTheScanAndTheLastSequenceIsTheLastGoodLine() throws IOException {
        Path file = writeLog(7);
        write(file, read(file) + "{\"sequence\":8,\"comm");

        LogScan scan = LogScan.of(file);

        assertEquals(7, scan.lastSequence());
        assertTrue(scan.tornTailDropped());
    }
}
