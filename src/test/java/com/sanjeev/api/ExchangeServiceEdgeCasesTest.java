package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.engine.MatchingEngine;
import com.sanjeev.engine.OrderType;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;
import com.sanjeev.infra.EventLog;
import com.sanjeev.infra.EventPublisher;
import com.sanjeev.infra.Sequencer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the exchange service does when things go wrong: the caller's thread is interrupted, or the
 * engine refuses an order the API let through. A web thread must always be released with a clear error.
 */
class ExchangeServiceEdgeCasesTest {

    @TempDir
    Path dir;

    private final CountDownLatch holdPublishing = new CountDownLatch(1);
    private final CountDownLatch publishing = new CountDownLatch(1);
    private SequencedExchangeService service;

    private SequencedExchangeService start(EventPublisher publisher) throws Exception {
        Sequencer sequencer = new Sequencer(new MatchingEngine(), new EventLog(dir.resolve("edge.log")), publisher, 64);
        sequencer.start();
        service = new SequencedExchangeService(sequencer, new RecentTrades(10), Duration.ofSeconds(30));
        return service;
    }

    @AfterEach
    void stop() {
        Thread.interrupted();         // never leak an interrupt into the next test
        holdPublishing.countDown();   // let a held engine thread finish
        if (service != null) {
            service.close();
        }
    }

    private static Domain.NewOrderSpec limit(String trader, long pricePaise, long quantity) {
        return new Domain.NewOrderSpec(trader, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, pricePaise, quantity);
    }

    @Test
    void anOrderTheEngineRefusesBecomesARejectionNotACrash() throws Exception {
        start((sequence, events) -> { });

        // the web layer would have refused a blank trader first; here it reaches the engine directly
        OrderRejectedException e = assertThrows(OrderRejectedException.class,
                () -> service.placeOrder(limit(" ", 4000, 5)));

        assertTrue(e.getMessage().startsWith("the order was rejected"), e.getMessage());
    }

    @Test
    void aCallerInterruptedBeforeHandingOverGetsUnavailableAndKeepsItsInterruptFlag() throws Exception {
        start((sequence, events) -> { });

        Thread.currentThread().interrupt();

        assertThrows(ExchangeUnavailableException.class, () -> service.cancelOrder(1));
        assertTrue(Thread.interrupted(), "the interrupt flag must be restored for whoever called us");
    }

    @Test
    void aBookQueryFromAnInterruptedCallerGetsUnavailableAndKeepsItsInterruptFlag() throws Exception {
        start((sequence, events) -> { });

        Thread.currentThread().interrupt();

        assertThrows(ExchangeUnavailableException.class, () -> service.book(5));
        assertTrue(Thread.interrupted());
    }

    @Test
    void aCallerInterruptedWhileWaitingForTheEngineIsReleasedAtOnce() throws Exception {
        start((sequence, events) -> {
            publishing.countDown();
            try {
                holdPublishing.await();   // the engine thread is busy, so the answer is not ready
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<Boolean> flagKept = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                service.placeOrder(limit("Anu", 4000, 5));
            } catch (Throwable t) {
                thrown.set(t);
                flagKept.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.start();
        assertTrue(publishing.await(10, TimeUnit.SECONDS), "the order never reached the engine");

        caller.interrupt();

        caller.join(10_000);
        assertFalse(caller.isAlive(), "an interrupted caller must not stay blocked");
        assertTrue(thrown.get() instanceof ExchangeUnavailableException, String.valueOf(thrown.get()));
        assertTrue(thrown.get().getMessage().contains("interrupted"));
        assertTrue(flagKept.get(), "the interrupt flag must be restored");
    }

    @Test
    void closingWhileInterruptedKeepsTheInterruptFlagAndTheExchangeCanStillBeClosedProperlyAfterwards() throws Exception {
        start((sequence, events) -> { });

        Thread.currentThread().interrupt();
        service.close();

        assertTrue(Thread.interrupted(), "close must not swallow the interrupt");
        // stop() closes it again, this time without an interrupt, which must succeed
    }

    @Test
    void aRunningExchangeReportsNoFailure() throws Exception {
        start((sequence, events) -> { });

        assertTrue(service.failure().isEmpty());
    }

    @Test
    void anExchangeWhoseEngineStoppedAfterAFailureSaysSoWithoutBlocking() throws Exception {
        start((sequence, events) -> {
            throw new IllegalStateException("subscriber blew up");
        });
        assertThrows(ExchangeUnavailableException.class, () -> service.placeOrder(limit("Anu", 4000, 5)));

        assertTrue(service.failure().isPresent());
        assertTrue(service.failure().orElseThrow().contains("subscriber blew up"));
    }
}
