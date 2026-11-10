package com.sanjeev.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.engine.Event;
import com.sanjeev.engine.MatchingEngine;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Results for callers ({@code submitForResult}) and read-only queries on the engine thread. */
class SequencerResultTest {

    @TempDir
    Path dir;

    private static final long WAIT_SECONDS = 10;

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(WAIT_SECONDS, TimeUnit.SECONDS); // never hang a test forever
    }

    private static Command sell(long id, long price, long qty) {
        return Command.NewOrder.limit(id, "Ravi", Side.SELL, price, qty, TimeInForce.GTC);
    }

    private Sequencer start(EventPublisher publisher) throws IOException {
        Sequencer sequencer = new Sequencer(new MatchingEngine(), new EventLog(dir.resolve("commands.log")),
                publisher, 64);
        sequencer.start();
        return sequencer;
    }

    // ---------------------------------------------------------------- submitForResult

    @Test
    void theFutureCarriesTheSequenceNumberAndTheEventsOfThatCommand() throws Exception {
        Sequencer sequencer = start((s, e) -> { });

        Sequencer.Applied applied = await(sequencer.submitForResult(sell(7, 3000, 10)));

        assertEquals(1, applied.sequence());
        Event.OrderAccepted accepted = assertInstanceOf(Event.OrderAccepted.class, applied.events().get(0));
        assertEquals(7, accepted.orderId());
        sequencer.shutdown();
    }

    @Test
    void sequentialCommandsGetConsecutiveSequenceNumbers() throws Exception {
        Sequencer sequencer = start((s, e) -> { });

        for (long expected = 1; expected <= 50; expected++) {
            assertEquals(expected, await(sequencer.submitForResult(sell(expected, 3000 + expected, 1))).sequence());
        }
        sequencer.shutdown();
    }

    @Test
    void aRefusedCommandStillGetsAnAnswerNotAnException() throws Exception {
        Sequencer sequencer = start((s, e) -> { });

        Sequencer.Applied applied = await(sequencer.submitForResult(new Command.CancelOrder(99)));

        assertInstanceOf(Event.OrderRejected.class, applied.events().get(0));
        sequencer.shutdown();
    }

    @Test
    void fourThreadsWaitingForTheirOwnResultsEachGetTheirOwnUniqueSequenceAndEvents() throws Exception {
        Sequencer sequencer = start((s, e) -> { });
        ConcurrentHashMap<Long, Long> sequenceByOrderId = new ConcurrentHashMap<>();
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            long base = t * 1_000_000L;
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 1; i <= 500; i++) {
                        long id = base + i;
                        Sequencer.Applied applied = await(sequencer.submitForResult(sell(id, 3000 + (id % 50), 1)));
                        Event.OrderAccepted accepted = (Event.OrderAccepted) applied.events().get(0);
                        assertEquals(id, accepted.orderId(), "got somebody else's events");
                        sequenceByOrderId.put(id, applied.sequence());
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        sequencer.shutdown();

        assertTrue(errors.isEmpty(), "errors: " + errors);
        assertEquals(2_000, sequenceByOrderId.size());
        assertEquals(2_000, sequenceByOrderId.values().stream().distinct().count()); // all unique
        assertEquals(1L, sequenceByOrderId.values().stream().min(Long::compare).orElseThrow());
        assertEquals(2_000L, sequenceByOrderId.values().stream().max(Long::compare).orElseThrow());
    }

    @Test
    void theFutureCompletesOnlyAfterTheCommandIsLoggedAndPublished() throws Exception {
        List<String> trace = new ArrayList<>(); // engine thread writes; read after the future completes
        RecordingWriter writer = new RecordingWriter(trace);
        Sequencer sequencer = new Sequencer(new MatchingEngine(), new EventLog(writer),
                (s, e) -> trace.add("publish"), 16);
        sequencer.start();

        await(sequencer.submitForResult(sell(1, 3000, 10)));

        // the future completing happens-after both of these, so they are visible here
        assertEquals(List.of("log", "publish"), List.copyOf(trace));
        sequencer.shutdown();
    }

    // ---------------------------------------------------------------- query

    @Test
    void aQueryRunsOnTheEngineThreadNotOnTheCallersThread() throws Exception {
        Sequencer sequencer = start((s, e) -> { });

        String threadName = await(sequencer.query(engine -> Thread.currentThread().getName()));

        assertEquals("sequencer-engine", threadName);
        assertFalse(threadName.equals(Thread.currentThread().getName()));
        sequencer.shutdown();
    }

    @Test
    void aQuerySeesEveryCommandSubmittedBeforeIt() throws Exception {
        Sequencer sequencer = start((s, e) -> { });

        sequencer.submit(sell(1, 3000, 10)); // not waited for
        sequencer.submit(sell(2, 3100, 10));
        int restingAfterTwo = await(sequencer.query(engine -> engine.book().restingOrderCount()));
        sequencer.submit(sell(3, 3200, 10));
        int restingAfterThree = await(sequencer.query(engine -> engine.book().restingOrderCount()));

        assertEquals(2, restingAfterTwo);
        assertEquals(3, restingAfterThree);
        sequencer.shutdown();
    }

    @Test
    void queriesAreNeitherNumberedNorLogged() throws Exception {
        Path log = dir.resolve("commands.log");
        Sequencer sequencer = start((s, e) -> { });
        await(sequencer.submitForResult(sell(1, 3000, 10)));
        for (int i = 0; i < 25; i++) {
            await(sequencer.query(engine -> engine.book().bestAsk()));
        }

        Sequencer.Applied next = await(sequencer.submitForResult(sell(2, 3100, 10)));
        sequencer.shutdown();

        assertEquals(2, next.sequence());                     // 25 queries used no numbers
        assertEquals(2, Files.readAllLines(log).size());      // and wrote no log lines
    }

    @Test
    void aQueryThatThrowsFailsOnlyItsOwnFutureAndTheEngineCarriesOn() throws Exception {
        Sequencer sequencer = start((s, e) -> { });

        CompletableFuture<Object> bad = sequencer.query(engine -> {
            throw new IllegalArgumentException("my reader is broken");
        });
        ExecutionException e = assertThrows(ExecutionException.class, () -> await(bad));
        Sequencer.Applied after = await(sequencer.submitForResult(sell(1, 3000, 10)));

        assertEquals("my reader is broken", e.getCause().getMessage());
        assertEquals(1, after.sequence());
        assertTrue(sequencer.failure().isEmpty());
        sequencer.shutdown();
    }

    // ---------------------------------------------------------------- failures never leave anyone waiting

    @Test
    void ifTheLogCannotBeWrittenEveryWaitingFutureFailsInsteadOfHanging() throws Exception {
        Sequencer sequencer = new Sequencer(new MatchingEngine(), new EventLog(new FailingWriter()),
                (s, e) -> { }, 64);
        sequencer.start();

        List<CompletableFuture<Sequencer.Applied>> futures = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            try {
                futures.add(sequencer.submitForResult(sell(i, 3000 + i, 1)));
            } catch (IllegalStateException alreadyStopped) {
                break; // the sequencer had already stopped: also a fine answer, and nothing waits
            }
        }
        CompletableFuture<List<Object>> aQuery = new CompletableFuture<>();
        try {
            sequencer.query(engine -> List.<Object>of()).whenComplete((v, t) -> {
                if (t != null) {
                    aQuery.completeExceptionally(t);
                } else {
                    aQuery.complete(v);
                }
            });
        } catch (IllegalStateException alreadyStopped) {
            aQuery.completeExceptionally(alreadyStopped);
        }

        assertFalse(futures.isEmpty());
        for (CompletableFuture<Sequencer.Applied> future : futures) {
            ExecutionException e = assertThrows(ExecutionException.class, () -> await(future));
            assertInstanceOf(IllegalStateException.class, e.getCause());
            assertTrue(e.getCause().getMessage().contains("disk full"));
        }
        assertThrows(ExecutionException.class, () -> await(aQuery));
        assertThrows(IllegalStateException.class, () -> sequencer.submitForResult(sell(99, 3000, 1)));
        sequencer.shutdown();
    }

    @Test
    void ifPublishingFailsTheCommandsFutureFailsToo() throws Exception {
        Sequencer sequencer = start((s, e) -> {
            throw new IllegalStateException("subscriber blew up");
        });

        CompletableFuture<Sequencer.Applied> future = sequencer.submitForResult(sell(1, 3000, 10));

        ExecutionException e = assertThrows(ExecutionException.class, () -> await(future));
        assertTrue(e.getCause().getMessage().contains("subscriber blew up"));
        sequencer.shutdown();
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    void shutdownStillAnswersEverythingThatWasAlreadyAccepted() throws Exception {
        Sequencer sequencer = start((s, e) -> { });
        List<CompletableFuture<Sequencer.Applied>> futures = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            futures.add(sequencer.submitForResult(sell(i, 3000 + i, 1)));
        }
        CompletableFuture<Integer> count = sequencer.query(engine -> engine.book().restingOrderCount());

        sequencer.shutdown();

        assertEquals(60, await(count));
        assertEquals(60, futures.stream().filter(CompletableFuture::isDone).count());
        assertEquals("[" + java.util.stream.LongStream.rangeClosed(1, 60).mapToObj(Long::toString)
                        .collect(Collectors.joining(", ")) + "]",
                futures.stream().map(f -> f.join().sequence()).toList().toString());
    }

    @Test
    void workSubmittedAfterShutdownIsRejected() throws Exception {
        Sequencer sequencer = start((s, e) -> { });
        sequencer.shutdown();

        assertThrows(IllegalStateException.class, () -> sequencer.submitForResult(sell(1, 3000, 1)));
        assertThrows(IllegalStateException.class, () -> sequencer.query(engine -> 1));
    }

    @Test
    void nullCommandsAndNullReadersAreRejected() throws Exception {
        Sequencer sequencer = start((s, e) -> { });

        assertThrows(IllegalArgumentException.class, () -> sequencer.submitForResult(null));
        assertThrows(IllegalArgumentException.class, () -> sequencer.query(null));
        sequencer.shutdown();
    }

    @Test
    void aFutureThatIsNeverAnsweredCanBeTimedOutByTheCallerSoNoRequestHangsForever() {
        CompletableFuture<Sequencer.Applied> neverAnswered = new CompletableFuture<>();

        assertThrows(TimeoutException.class, () -> neverAnswered.get(50, TimeUnit.MILLISECONDS));
    }
}
