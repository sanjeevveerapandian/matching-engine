package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanjeev.engine.Event;
import com.sanjeev.engine.MatchingEngine;
import com.sanjeev.engine.OrderType;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;
import com.sanjeev.infra.EventLog;
import com.sanjeev.infra.EventPublisher;
import com.sanjeev.infra.Sequencer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The simulator against a REAL exchange (sequencer, engine, log file): no Spring, no HTTP. */
class TrafficSimulatorTest {

    @TempDir
    Path dir;

    private final List<SequencedExchangeService> started = new ArrayList<>();

    @AfterEach
    void stop() {
        started.forEach(SequencedExchangeService::close);
    }

    private SequencedExchangeService exchange(String logName) throws IOException {
        RecentTrades recentTrades = new RecentTrades(100);
        EventPublisher publisher = (sequence, events) -> {
            for (Event event : events) {
                if (event instanceof Event.TradeExecuted executed) {
                    recentTrades.add(executed.trade());
                }
            }
        };
        Sequencer sequencer = new Sequencer(new MatchingEngine(), new EventLog(dir.resolve(logName)), publisher, 256);
        sequencer.start();
        SequencedExchangeService service = new SequencedExchangeService(sequencer, recentTrades, Duration.ofSeconds(10));
        started.add(service);
        return service;
    }

    private static int total(Domain.SimulationResult r) {
        return r.limitOrders() + r.marketOrders() + r.cancels() + r.modifies();
    }

    @Test
    void everyRequestedOperationIsCountedInExactlyOneKind() throws Exception {
        SequencedExchangeService service = exchange("a.log");

        Domain.SimulationResult result = new TrafficSimulator(service).run(1_000, 42);

        assertEquals(1_000, result.requested());
        assertEquals(1_000, total(result));
    }

    @Test
    void theMixHasEveryKindOfOperationAndMakesTradesAndLeavesAnOrderBook() throws Exception {
        SequencedExchangeService service = exchange("a.log");

        Domain.SimulationResult result = new TrafficSimulator(service).run(2_000, 42);

        assertTrue(result.limitOrders() > 800, "limit orders: " + result.limitOrders());
        assertTrue(result.marketOrders() > 100, "market orders: " + result.marketOrders());
        assertTrue(result.cancels() > 200, "cancels: " + result.cancels());
        assertTrue(result.modifies() > 100, "modifies: " + result.modifies());
        assertTrue(result.trades() > 100, "trades: " + result.trades());
        Domain.BookSnapshot book = service.book(10);
        assertTrue(!book.bids().isEmpty() && !book.asks().isEmpty(), "both sides of the book should have orders");
    }

    @Test
    void theSameSeedMakesTheSameRequestsSoTheSameOutcome() throws Exception {
        Domain.SimulationResult first = new TrafficSimulator(exchange("a.log")).run(1_500, 7);
        Domain.SimulationResult second = new TrafficSimulator(exchange("b.log")).run(1_500, 7);

        assertEquals(first, second);
    }

    @Test
    void differentSeedsMakeDifferentTraffic() throws Exception {
        Domain.SimulationResult first = new TrafficSimulator(exchange("a.log")).run(1_500, 1);
        Domain.SimulationResult second = new TrafficSimulator(exchange("b.log")).run(1_500, 2);

        assertNotEquals(first, second);
    }

    @Test
    void theBookIsNeverLeftCrossed() throws Exception {
        SequencedExchangeService service = exchange("a.log");

        new TrafficSimulator(service).run(3_000, 11);

        Domain.BookSnapshot book = service.book(1);
        assertTrue(book.bestBid() != null && book.bestAsk() != null, "a busy market has both sides");
        assertTrue(book.bestBid() < book.bestAsk(), "best bid " + book.bestBid() + " best ask " + book.bestAsk());
    }

    @Test
    void everySimulatedOperationWentThroughTheSequencerIntoTheLog() throws Exception {
        SequencedExchangeService service = exchange("a.log");

        new TrafficSimulator(service).run(500, 3);
        service.close();

        // 500 operations = 500 commands: cancels and modifies of an order that was already gone are
        // still commands (they are refused, and logged). The book queries it made are not.
        assertEquals(500, Files.readAllLines(dir.resolve("a.log")).size());
    }

    @Test
    void anEmptyBookStartsNearTheDefaultPriceOfFortyRupees() throws Exception {
        SequencedExchangeService service = exchange("a.log");

        new TrafficSimulator(service).run(20, 5);

        Domain.BookSnapshot book = service.book(10);
        List<Long> prices = new ArrayList<>();
        book.bids().forEach(level -> prices.add(level.priceInPaise()));
        book.asks().forEach(level -> prices.add(level.priceInPaise()));
        assertTrue(!prices.isEmpty(), "the simulation should leave some orders resting");
        for (long price : prices) {
            assertTrue(price > 3900 && price < 4100, "price " + price);
        }
    }

    @Test
    void zeroOperationsDoNothing() throws Exception {
        SequencedExchangeService service = exchange("a.log");

        Domain.SimulationResult result = new TrafficSimulator(service).run(0, 1);

        assertEquals(new Domain.SimulationResult(0, 0, 0, 0, 0, 0, 0, 0), result);
        assertEquals(0, service.book(5).bids().size() + service.book(5).asks().size());
    }

    @Test
    void ifTheExchangeStopsTheSimulationStopsWithUnavailableInsteadOfHanging() throws Exception {
        SequencedExchangeService service = exchange("a.log");
        service.close();

        assertThrows(ExchangeUnavailableException.class, () -> new TrafficSimulator(service).run(10, 1));
    }

    private static void assertAllRestingPricesWithin(Domain.BookSnapshot book, long low, long high) {
        List<Long> prices = new ArrayList<>();
        book.bids().forEach(level -> prices.add(level.priceInPaise()));
        book.asks().forEach(level -> prices.add(level.priceInPaise()));
        assertTrue(!prices.isEmpty(), "the simulation should leave some orders resting");
        for (long price : prices) {
            assertTrue(price > low && price < high, "price " + price + " is not near the market");
        }
    }

    @Test
    void withOnlyBuyersOnTheBookTheSimulationStartsNearTheBestBid() throws Exception {
        SequencedExchangeService service = exchange("a.log");
        service.placeOrder(new Domain.NewOrderSpec("Seed", Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 6000L, 5));

        new TrafficSimulator(service).run(20, 5);

        assertAllRestingPricesWithin(service.book(50), 5900, 6100);
    }

    @Test
    void withOnlySellersOnTheBookTheSimulationStartsNearTheBestAsk() throws Exception {
        SequencedExchangeService service = exchange("a.log");
        service.placeOrder(new Domain.NewOrderSpec("Seed", Side.SELL, OrderType.LIMIT, TimeInForce.GTC, 2000L, 5));

        new TrafficSimulator(service).run(20, 5);

        assertAllRestingPricesWithin(service.book(50), 1900, 2100);
    }

    @Test
    void ordersTheExchangeRefusesAreCountedAsRejectedAndTheSimulationCarriesOn() {
        ExchangeService refusing = mock(ExchangeService.class);
        when(refusing.book(anyInt())).thenReturn(new Domain.BookSnapshot(List.of(), List.of(), null, null));
        when(refusing.placeOrder(any())).thenThrow(new OrderRejectedException("closed for the day"));

        Domain.SimulationResult result = new TrafficSimulator(refusing).run(40, 1);

        assertEquals(40, result.rejected());
        assertEquals(40, result.limitOrders() + result.marketOrders());
        assertEquals(0, result.trades());
    }
}
