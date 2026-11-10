package com.sanjeev.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.engine.MatchingEngine;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * The unhappy paths of the sequencer: the engine thread dies, the log will not close, producers are
 * stuck behind a full queue. Whatever happens, nobody may wait forever and nothing unlogged may run.
 */
class SequencerFailureTest {

    private static Command order(long id) {
        return Command.NewOrder.limit(id, "Ravi", Side.SELL, 3000 + id, 10, TimeInForce.GTC);
    }

    private static void waitUntil(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("gave up waiting for: " + what);
            }
            Thread.sleep(5);
        }
    }

    @Test
    void ifTheEngineThreadIsInterruptedTheSequencerStopsSaysWhyAndRefusesNewWork() throws Exception {
        AtomicReference<Thread> engineThread = new AtomicReference<>();
        Writer spy = new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) {
                engineThread.set(Thread.currentThread()); // the log is written ON the engine thread
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        Sequencer sequencer = new Sequencer(new MatchingEngine(), new EventLog(spy), (sequence, events) -> { }, 16);
        sequencer.start();
        sequencer.submitForResult(order(1)).get(10, TimeUnit.SECONDS); // the engine thread is now idle, waiting for work

        engineThread.get().interrupt();

        waitUntil(() -> sequencer.failure().isPresent(), "the sequencer to notice the interruption");
        assertInstanceOf(IllegalStateException.class, sequencer.failure().orElseThrow());
        assertTrue(sequencer.failure().orElseThrow().getMessage().contains("interrupted"));
        assertThrows(IllegalStateException.class, () -> sequencer.submit(order(2)));
        sequencer.shutdown();
    }

    @Test
    void ifTheLogCannotBeClosedAtTheEndTheFailureIsReportedNotSwallowed() throws Exception {
        Writer logThatWillNotClose = new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) { }

            @Override
            public void flush() { }

            @Override
            public void close() throws IOException {
                throw new IOException("close failed");
            }
        };
        Sequencer sequencer = new Sequencer(new MatchingEngine(), new EventLog(logThatWillNotClose),
                (sequence, events) -> { }, 16);
        sequencer.start();

        sequencer.submit(order(1));
        sequencer.shutdown();

        assertEquals("close failed", sequencer.failure().orElseThrow().getMessage());
    }

    @Test
    void aProducerWaitingBehindAFullQueueGivesUpWhenTheEngineThreadDies() throws Exception {
        GatedWriter disk = new GatedWriter(true); // holds the engine thread, then fails
        Sequencer sequencer = new Sequencer(new MatchingEngine(), new EventLog(disk), (sequence, events) -> { }, 1);
        sequencer.start();
        sequencer.submit(order(1));   // the engine thread takes it and sticks in the log write
        disk.awaitEntered();
        sequencer.submit(order(2));   // fills the queue (capacity 1)
        AtomicReference<Throwable> thirdProducerResult = new AtomicReference<>();
        Thread thirdProducer = new Thread(() -> {
            try {
                sequencer.submitForResult(order(3)); // no room: waits. (A future nobody completes would hang its caller.)
            } catch (Throwable t) {
                thirdProducerResult.set(t);
            }
        });
        thirdProducer.start();
        waitUntil(() -> thirdProducer.getState() == Thread.State.TIMED_WAITING, "the third producer to be waiting");

        disk.release(); // the write fails: the engine thread dies

        thirdProducer.join(10_000);
        assertFalse(thirdProducer.isAlive(), "the producer must not wait forever on a dead engine thread");
        assertInstanceOf(IllegalStateException.class, thirdProducerResult.get());
        assertInstanceOf(IOException.class, sequencer.failure().orElseThrow());
        sequencer.shutdown();
    }

    @Test
    void shutdownWaitsForAFullQueueToDrainAndStillProcessesEverythingAccepted() throws Exception {
        GatedWriter disk = new GatedWriter(false); // holds the engine thread, then lets it carry on
        List<Long> published = new CopyOnWriteArrayList<>();
        Sequencer sequencer = new Sequencer(new MatchingEngine(), new EventLog(disk),
                (sequence, events) -> published.add(sequence), 1);
        sequencer.start();
        sequencer.submit(order(1));
        disk.awaitEntered();
        sequencer.submit(order(2));   // the queue is full: shutdown cannot even enqueue its STOP marker yet
        Thread shutdown = new Thread(() -> {
            try {
                sequencer.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        shutdown.start();
        waitUntil(() -> shutdown.getState() == Thread.State.TIMED_WAITING, "shutdown to be waiting for room");

        disk.release();

        shutdown.join(10_000);
        assertFalse(shutdown.isAlive(), "shutdown never finished");
        assertEquals(List.of(1L, 2L), published);
        assertTrue(sequencer.failure().isEmpty());
    }
}
