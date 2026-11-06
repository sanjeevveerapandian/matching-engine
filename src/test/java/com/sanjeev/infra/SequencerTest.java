package com.sanjeev.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.engine.BookInvariants;
import com.sanjeev.engine.Event;
import com.sanjeev.engine.MatchingEngine;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SequencerTest {

    @TempDir
    Path dir;

    /** Runs ON the engine thread after every command: records it and checks the whole book. */
    private static final class Collector implements EventPublisher {
        final MatchingEngine engine;
        final List<Long> sequences = new ArrayList<>();
        final List<Event> events = new ArrayList<>();

        Collector(MatchingEngine engine) {
            this.engine = engine;
        }

        @Override
        public void publish(long sequence, List<Event> published) {
            sequences.add(sequence);
            events.addAll(published);
            BookInvariants.check(engine.book()); // safe: same thread as the engine
        }
    }

    /** A sequencer writing a real log file, started and ready for producers. */
    private final class Run {
        final MatchingEngine engine = new MatchingEngine();
        final Collector collector = new Collector(engine);
        final Path logFile = dir.resolve("commands.log");
        final Sequencer sequencer;

        Run(int queueCapacity) throws IOException {
            sequencer = new Sequencer(engine, new EventLog(logFile), collector, queueCapacity);
            sequencer.start();
        }
    }

    @FunctionalInterface
    private interface ProducerBody {
        void run(int producerIndex) throws Exception;
    }

    /** Starts the producers together, waits for all of them, and fails if any of them failed. */
    private static void runProducers(int count, ProducerBody body) throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                    body.run(index);
                } catch (Throwable e) {
                    errors.add(e);
                }
            }, "producer-" + i);
            threads.add(thread);
            thread.start();
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        assertTrue(errors.isEmpty(), "a producer failed: " + errors);
    }

    private static List<Long> oneTo(int n) {
        return LongStream.rangeClosed(1, n).boxed().toList();
    }

    private static Command.NewOrder randomLimit(long id, int producer, Random random) {
        return Command.NewOrder.limit(id, "T" + producer,
                random.nextBoolean() ? Side.BUY : Side.SELL,
                2980 + random.nextInt(41), 1 + random.nextInt(100), TimeInForce.GTC);
    }

    private static List<Event> replay(Path logFile, MatchingEngine fresh) throws IOException {
        CommandProcessor processor = new CommandProcessor(fresh);
        List<Event> events = new ArrayList<>();
        for (LogEntry entry : EventLog.readAll(logFile)) {
            events.addAll(processor.apply(entry.sequence(), entry.command()));
        }
        return events;
    }

    // ---------------------------------------------------------------- the main concurrency test

    @Test
    void fourThreadsSubmittingTenThousandOrdersEachGetGapFreeUniqueSequenceNumbers() throws Exception {
        int threads = 4;
        int perThread = 10_000;
        int total = threads * perThread;
        Run run = new Run(1024);

        runProducers(threads, t -> {
            Random random = new Random(1000 + t);
            for (int i = 1; i <= perThread; i++) {
                run.sequencer.submit(randomLimit(t * 1_000_000L + i, t, random));
            }
        });
        run.sequencer.shutdown();

        assertTrue(run.sequencer.failure().isEmpty(), "sequencer failed: " + run.sequencer.failure());

        // sequence numbers: exactly 1..40000, in order, no gaps, no duplicates
        assertEquals(oneTo(total), run.collector.sequences);

        // the log: exactly one line per command, in sequence order
        assertEquals(total, Files.readAllLines(run.logFile).size());
        List<LogEntry> entries = EventLog.readAll(run.logFile);
        assertEquals(oneTo(total), entries.stream().map(LogEntry::sequence).toList());

        // every command exactly once, and each producer's own commands kept their order
        Set<Long> ids = new HashSet<>();
        long[] lastIdOfProducer = new long[threads];
        for (LogEntry entry : entries) {
            long id = ((Command.NewOrder) entry.command()).orderId();
            assertTrue(ids.add(id), "order " + id + " was logged twice");
            int producer = (int) (id / 1_000_000);
            assertTrue(id > lastIdOfProducer[producer], "producer " + producer + " was reordered");
            lastIdOfProducer[producer] = id;
        }
        assertEquals(total, ids.size());

        // invariants held after every command (the collector checked), and still hold now
        BookInvariants.check(run.sequencer.engineAfterShutdown().book());

        // and the engine really did work: all accepted, some traded
        long accepted = run.collector.events.stream().filter(e -> e instanceof Event.OrderAccepted).count();
        long rejected = run.collector.events.stream().filter(e -> e instanceof Event.OrderRejected).count();
        long trades = run.collector.events.stream().filter(e -> e instanceof Event.TradeExecuted).count();
        assertEquals(total, accepted);
        assertEquals(0, rejected);
        assertTrue(trades > 0);
    }

    @Test
    void aMixOfOrdersCancelsAndModifiesFromFourThreadsKeepsEveryInvariantAndTheLogComplete()
            throws Exception {
        int threads = 4;
        int perThread = 5_000;
        int total = threads * perThread;
        Run run = new Run(256);

        runProducers(threads, t -> {
            Random random = new Random(2000 + t);
            List<Long> mine = new ArrayList<>();
            long counter = 0;
            for (int i = 0; i < perThread; i++) {
                int roll = random.nextInt(100);
                Command command;
                if (roll >= 75 && !mine.isEmpty()) {
                    long target = mine.get(random.nextInt(mine.size()));
                    command = roll < 90
                            ? new Command.CancelOrder(target)
                            : new Command.ModifyOrder(target, 2980 + random.nextInt(41), 1 + random.nextInt(150));
                } else if (roll >= 65 && roll < 75) {
                    command = Command.NewOrder.market(t * 1_000_000L + (++counter), "T" + t,
                            random.nextBoolean() ? Side.BUY : Side.SELL, 1 + random.nextInt(100));
                } else {
                    long id = t * 1_000_000L + (++counter);
                    mine.add(id);
                    command = randomLimit(id, t, random);
                }
                run.sequencer.submit(command);
            }
        });
        run.sequencer.shutdown();

        assertTrue(run.sequencer.failure().isEmpty(), "sequencer failed: " + run.sequencer.failure());
        assertEquals(oneTo(total), run.collector.sequences);
        assertEquals(total, Files.readAllLines(run.logFile).size());
        assertEquals(oneTo(total), EventLog.readAll(run.logFile).stream().map(LogEntry::sequence).toList());
        BookInvariants.check(run.sequencer.engineAfterShutdown().book());
    }

    // ---------------------------------------------------------------- write-ahead log

    @Test
    void replayingTheLogIntoAFreshEngineGivesExactlyTheSameEventsAndTheSameBook() throws Exception {
        Run run = new Run(256);
        runProducers(4, t -> {
            Random random = new Random(3000 + t);
            List<Long> mine = new ArrayList<>();
            for (int i = 1; i <= 3_000; i++) {
                long id = t * 1_000_000L + i;
                mine.add(id);
                int roll = random.nextInt(100);
                if (roll < 70) {
                    run.sequencer.submit(randomLimit(id, t, random));
                } else if (roll < 85) {
                    run.sequencer.submit(new Command.CancelOrder(mine.get(random.nextInt(mine.size()))));
                } else {
                    run.sequencer.submit(new Command.ModifyOrder(mine.get(random.nextInt(mine.size())),
                            2980 + random.nextInt(41), 1 + random.nextInt(150)));
                }
            }
        });
        run.sequencer.shutdown();
        assertTrue(run.sequencer.failure().isEmpty(), "sequencer failed: " + run.sequencer.failure());

        MatchingEngine recovered = new MatchingEngine();
        List<Event> replayedEvents = replay(run.logFile, recovered);

        assertEquals(run.collector.events, replayedEvents);
        assertEquals(run.sequencer.engineAfterShutdown().book().toString(), recovered.book().toString());
    }

    @Test
    void everyCommandIsWrittenToTheLogBeforeItsEventsArePublished() throws Exception {
        List<String> trace = new ArrayList<>(); // only the engine thread writes to it
        MatchingEngine engine = new MatchingEngine();
        Sequencer sequencer = new Sequencer(engine, new EventLog(new RecordingWriter(trace)),
                (sequence, events) -> trace.add("publish"), 16);
        sequencer.start();

        for (int i = 1; i <= 100; i++) {
            sequencer.submit(Command.NewOrder.limit(i, "Ravi", Side.SELL, 3000 + i, 10, TimeInForce.GTC));
        }
        sequencer.shutdown();

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            expected.add("log");
            expected.add("publish");
        }
        assertEquals(expected, trace);
    }

    @Test
    void theCommandIsInTheLogBeforeTheEngineChangesTheBook() throws Exception {
        MatchingEngine engine = new MatchingEngine();
        List<Integer> restingWhenLogged = new ArrayList<>(); // only the engine thread touches it
        Writer spy = new Writer() {
            @Override
            public void write(String line) {
                restingWhenLogged.add(engine.book().restingOrderCount()); // book as the log is written
            }

            @Override
            public void write(char[] buffer, int offset, int length) {
                write(new String(buffer, offset, length));
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        Sequencer sequencer = new Sequencer(engine, new EventLog(spy), (sequence, events) -> { }, 16);
        sequencer.start();

        // each order rests (sells only, so nothing crosses): order i should find i-1 orders on the book
        for (int i = 1; i <= 50; i++) {
            sequencer.submit(Command.NewOrder.limit(i, "Ravi", Side.SELL, 3000 + i, 10, TimeInForce.GTC));
        }
        sequencer.shutdown();

        assertEquals(LongStream.range(0, 50).boxed().map(Long::intValue).toList(), restingWhenLogged);
    }

    @Test
    void ifTheLogCannotBeWrittenTheCommandIsNeverAppliedAndTheSequencerStops() throws Exception {
        MatchingEngine engine = new MatchingEngine();
        Collector collector = new Collector(engine);
        Sequencer sequencer = new Sequencer(engine, new EventLog(new FailingWriter()), collector, 16);
        sequencer.start();

        sequencer.submit(Command.NewOrder.limit(1, "Ravi", Side.SELL, 3000, 10, TimeInForce.GTC));
        sequencer.shutdown();

        assertTrue(sequencer.failure().orElseThrow() instanceof IOException);
        assertTrue(collector.events.isEmpty(), "nothing may be published for an unlogged command");
        assertEquals(0, sequencer.engineAfterShutdown().book().restingOrderCount());
        assertThrows(IllegalStateException.class, () -> sequencer.submit(new Command.CancelOrder(1)));
    }

    @Test
    void aFailureWhilePublishingStopsTheSequencerButTheCommandIsAlreadyInTheLog() throws Exception {
        RecordingWriter writer = new RecordingWriter();
        Sequencer sequencer = new Sequencer(new MatchingEngine(), new EventLog(writer),
                (sequence, events) -> {
                    throw new IllegalStateException("subscriber blew up");
                }, 16);
        sequencer.start();

        sequencer.submit(Command.NewOrder.limit(1, "Ravi", Side.SELL, 3000, 10, TimeInForce.GTC));
        sequencer.shutdown();

        assertTrue(sequencer.failure().orElseThrow().getMessage().contains("subscriber blew up"));
        assertEquals(1, writer.writes.size()); // logged first, so a replay would still find it
    }

    // ---------------------------------------------------------------- lifecycle and back-pressure

    @Test
    void aTinyQueueMakesProducersWaitButNoCommandIsLost() throws Exception {
        int threads = 4;
        int perThread = 1_000;
        Run run = new Run(2);

        runProducers(threads, t -> {
            Random random = new Random(4000 + t);
            for (int i = 1; i <= perThread; i++) {
                run.sequencer.submit(randomLimit(t * 1_000_000L + i, t, random));
            }
        });
        run.sequencer.shutdown();

        assertEquals(oneTo(threads * perThread), run.collector.sequences);
    }

    @Test
    void shutdownProcessesEverythingAlreadyAccepted() throws Exception {
        Run run = new Run(64);
        Random random = new Random(5);
        for (int i = 1; i <= 5_000; i++) {
            run.sequencer.submit(randomLimit(i, 0, random));
        }

        run.sequencer.shutdown(); // called straight away, with commands still queued

        assertEquals(oneTo(5_000), run.collector.sequences);
        assertEquals(5_000, Files.readAllLines(run.logFile).size());
    }

    @Test
    void submittingAfterShutdownIsRejected() throws Exception {
        Run run = new Run(16);
        run.sequencer.shutdown();

        assertThrows(IllegalStateException.class, () -> run.sequencer.submit(new Command.CancelOrder(1)));
    }

    @Test
    void aNullCommandIsRejected() throws Exception {
        Run run = new Run(16);

        assertThrows(IllegalArgumentException.class, () -> run.sequencer.submit(null));
        run.sequencer.shutdown();
    }

    @Test
    void theEngineCannotBeInspectedWhileTheEngineThreadIsStillRunning() throws Exception {
        Run run = new Run(16);

        assertThrows(IllegalStateException.class, () -> run.sequencer.engineAfterShutdown());

        run.sequencer.shutdown();
        run.sequencer.engineAfterShutdown(); // fine now
    }
}
