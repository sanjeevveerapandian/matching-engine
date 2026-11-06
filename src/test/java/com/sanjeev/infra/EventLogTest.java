package com.sanjeev.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventLogTest {

    @TempDir
    Path dir;

    private static final List<Command> SAMPLE = List.of(
            Command.NewOrder.limit(1, "Ravi", Side.SELL, 3000, 10, TimeInForce.GTC),
            Command.NewOrder.market(2, "Anu", Side.BUY, 5),
            new Command.ModifyOrder(1, 3100, 8),
            new Command.CancelOrder(1));

    private void appendAll(EventLog log) throws IOException {
        long sequence = 1;
        for (Command command : SAMPLE) {
            log.append(sequence++, command);
        }
    }

    @Test
    void everyCommandBecomesExactlyOneLineStartingWithItsSequenceNumber() throws IOException {
        Path file = dir.resolve("commands.log");
        try (EventLog log = new EventLog(file)) {
            appendAll(log);
        }

        List<String> lines = Files.readAllLines(file);

        assertEquals(SAMPLE.size(), lines.size());
        for (int i = 0; i < lines.size(); i++) {
            assertTrue(lines.get(i).startsWith("{\"sequence\":" + (i + 1) + ","), lines.get(i));
        }
    }

    @Test
    void eachLineSaysWhichKindOfCommandItIs() throws IOException {
        Path file = dir.resolve("commands.log");
        try (EventLog log = new EventLog(file)) {
            appendAll(log);
        }

        List<String> lines = Files.readAllLines(file);

        assertTrue(lines.get(0).contains("\"kind\":\"NewOrder\""));
        assertTrue(lines.get(2).contains("\"kind\":\"ModifyOrder\""));
        assertTrue(lines.get(3).contains("\"kind\":\"CancelOrder\""));
    }

    @Test
    void everyKindOfCommandSurvivesTheRoundTripUnchanged() throws IOException {
        Path file = dir.resolve("commands.log");
        try (EventLog log = new EventLog(file)) {
            appendAll(log);
        }

        List<LogEntry> entries = EventLog.readAll(file);

        assertEquals(SAMPLE.size(), entries.size());
        for (int i = 0; i < SAMPLE.size(); i++) {
            assertEquals(i + 1, entries.get(i).sequence());
            assertEquals(SAMPLE.get(i), entries.get(i).command());
        }
    }

    @Test
    void aMarketOrdersMissingPriceIsStoredAsNullAndComesBackAsNull() throws IOException {
        Path file = dir.resolve("commands.log");
        try (EventLog log = new EventLog(file)) {
            log.append(1, Command.NewOrder.market(2, "Anu", Side.BUY, 5));
        }

        Command.NewOrder read = (Command.NewOrder) EventLog.readAll(file).get(0).command();

        assertNull(read.priceInPaise());
    }

    @Test
    void aCommandWithANewlineInsideAFieldStillTakesExactlyOneLine() throws IOException {
        Path file = dir.resolve("commands.log");
        Command nasty = Command.NewOrder.limit(1, "Ra\nvi", Side.SELL, 3000, 10, TimeInForce.GTC);
        try (EventLog log = new EventLog(file)) {
            log.append(1, nasty);
        }

        assertEquals(1, Files.readAllLines(file).size());
        assertEquals(nasty, EventLog.readAll(file).get(0).command());
    }

    @Test
    void appendFlushesAfterEveryWrite() throws IOException {
        RecordingWriter writer = new RecordingWriter();
        EventLog log = new EventLog(writer);

        for (int i = 1; i <= 5; i++) {
            log.append(i, new Command.CancelOrder(i));
            assertEquals(i, writer.flushes, "flush must happen inside append, before it returns");
        }
        assertEquals(5, writer.writes.size());
    }

    @Test
    void aLineIsAlreadyInTheFileBeforeTheLogIsClosed() throws IOException {
        Path file = dir.resolve("commands.log");
        try (EventLog log = new EventLog(file)) {
            log.append(1, new Command.CancelOrder(7));

            assertEquals(1, Files.readAllLines(file).size()); // visible while still open
        }
    }

    @Test
    void anExistingLogFileIsNeverReopenedSoTwoRunsCannotMixTheirSequenceNumbers() throws IOException {
        Path file = dir.resolve("commands.log");
        new EventLog(file).close();

        assertThrows(FileAlreadyExistsException.class, () -> new EventLog(file));
    }
}
