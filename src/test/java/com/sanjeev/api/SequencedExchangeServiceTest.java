package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.engine.Event;
import com.sanjeev.engine.MatchingEngine;
import com.sanjeev.engine.OrderBook;
import com.sanjeev.engine.OrderType;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;
import com.sanjeev.engine.Trade;
import com.sanjeev.infra.EventLog;
import com.sanjeev.infra.EventPublisher;
import com.sanjeev.infra.Sequencer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The exchange service over a REAL sequencer, engine and log file: no Spring, no HTTP. */
class SequencedExchangeServiceTest {

    @TempDir
    Path dir;

    private SequencedExchangeService service;

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
    }

    private SequencedExchangeService start(Duration timeout, EventPublisher extra) throws IOException {
        RecentTrades recentTrades = new RecentTrades(50);
        EventPublisher publisher = (sequence, events) -> {
            for (Event event : events) {
                if (event instanceof Event.TradeExecuted executed) {
                    recentTrades.add(executed.trade());
                }
            }
            extra.publish(sequence, events);
        };
        Sequencer sequencer = new Sequencer(new MatchingEngine(), new EventLog(dir.resolve("commands.log")),
                publisher, 64);
        sequencer.start();
        service = new SequencedExchangeService(sequencer, recentTrades, timeout);
        return service;
    }

    private SequencedExchangeService start() throws IOException {
        return start(Duration.ofSeconds(10), (s, e) -> { });
    }

    private static Domain.NewOrderSpec limit(String trader, Side side, long pricePaise, long qty, TimeInForce tif) {
        return new Domain.NewOrderSpec(trader, side, OrderType.LIMIT, tif, pricePaise, qty);
    }

    private static Domain.NewOrderSpec gtc(String trader, Side side, long pricePaise, long qty) {
        return limit(trader, side, pricePaise, qty, TimeInForce.GTC);
    }

    private static Domain.NewOrderSpec market(String trader, Side side, long qty) {
        return new Domain.NewOrderSpec(trader, side, OrderType.MARKET, TimeInForce.IOC, null, qty);
    }

    // ---------------------------------------------------------------- placing orders

    @Test
    void anOrderThatDoesNotCrossRestsAndGetsAnIdAndASequenceNumber() throws Exception {
        start();

        Domain.PlacedOrder placed = service.placeOrder(gtc("Ravi", Side.SELL, 4000, 10));

        assertEquals(1, placed.orderId());
        assertEquals(1, placed.sequence());
        assertEquals("RESTING", placed.status());
        assertEquals(10, placed.restingQuantity());
        assertEquals(0, placed.filledQuantity());
        assertTrue(placed.trades().isEmpty());
    }

    @Test
    void orderIdsCountUpAndSequenceNumbersFollowTheCommandOrder() throws Exception {
        start();

        Domain.PlacedOrder first = service.placeOrder(gtc("Ravi", Side.SELL, 4000, 10));
        Domain.PlacedOrder second = service.placeOrder(gtc("Priya", Side.SELL, 4100, 10));

        assertEquals(List.of(1L, 2L), List.of(first.orderId(), second.orderId()));
        assertEquals(List.of(1L, 2L), List.of(first.sequence(), second.sequence()));
    }

    @Test
    void aCrossingOrderTradesAtTheRestingPriceAndIsFilled() throws Exception {
        start();
        service.placeOrder(gtc("Ravi", Side.SELL, 4000, 10));

        Domain.PlacedOrder placed = service.placeOrder(gtc("Anu", Side.BUY, 4200, 4));

        assertEquals("FILLED", placed.status());
        assertEquals(4, placed.filledQuantity());
        assertEquals(0, placed.restingQuantity());
        Trade trade = placed.trades().get(0);
        assertEquals("Anu", trade.buyer());
        assertEquals("Ravi", trade.seller());
        assertEquals(4000, trade.price());           // the resting order's price, in paise
        assertEquals(4, trade.quantity());
        assertEquals(2, trade.buyOrderId());
        assertEquals(1, trade.sellOrderId());
    }

    @Test
    void aPartlyFilledLimitOrderRestsItsRemainder() throws Exception {
        start();
        service.placeOrder(gtc("Ravi", Side.SELL, 4000, 4));

        Domain.PlacedOrder placed = service.placeOrder(gtc("Anu", Side.BUY, 4000, 10));

        assertEquals("PARTIALLY_FILLED", placed.status());
        assertEquals(4, placed.filledQuantity());
        assertEquals(6, placed.restingQuantity());
    }

    @Test
    void anIocOrderThatCannotFillCompletelyHasItsRestCancelled() throws Exception {
        start();
        service.placeOrder(gtc("Ravi", Side.SELL, 4000, 4));

        Domain.PlacedOrder partly = service.placeOrder(limit("Anu", Side.BUY, 4000, 10, TimeInForce.IOC));
        Domain.PlacedOrder nothing = service.placeOrder(limit("Anu", Side.BUY, 3000, 10, TimeInForce.IOC));

        assertEquals("PARTIALLY_FILLED_REST_CANCELLED", partly.status());
        assertEquals(6, partly.cancelledQuantity());
        assertEquals(0, partly.restingQuantity());
        assertEquals("CANCELLED", nothing.status());
        assertEquals(10, nothing.cancelledQuantity());
    }

    @Test
    void aFokOrderThatCannotBeFilledIsCancelledWholeAndTradesNothing() throws Exception {
        start();
        service.placeOrder(gtc("Ravi", Side.SELL, 4000, 4));

        Domain.PlacedOrder placed = service.placeOrder(limit("Anu", Side.BUY, 4000, 10, TimeInForce.FOK));

        assertEquals("CANCELLED", placed.status());
        assertEquals(0, placed.filledQuantity());
        assertEquals(10, placed.cancelledQuantity());
        assertTrue(placed.trades().isEmpty());
        assertEquals(4, service.book(5).asks().get(0).totalQuantity()); // Ravi untouched
    }

    @Test
    void aMarketOrderFillsAgainstTheBookAndNeverRests() throws Exception {
        start();
        service.placeOrder(gtc("Ravi", Side.SELL, 4000, 10));

        Domain.PlacedOrder filled = service.placeOrder(market("Anu", Side.BUY, 4));
        Domain.PlacedOrder tooBig = service.placeOrder(market("Anu", Side.BUY, 100));

        assertEquals("FILLED", filled.status());
        assertEquals("PARTIALLY_FILLED_REST_CANCELLED", tooBig.status());
        assertEquals(6, tooBig.filledQuantity());
        assertEquals(94, tooBig.cancelledQuantity());
        assertTrue(service.book(5).bids().isEmpty());
    }

    @Test
    void aMarketOrderOnAnEmptyBookIsCancelled() throws Exception {
        start();

        Domain.PlacedOrder placed = service.placeOrder(market("Anu", Side.BUY, 5));

        assertEquals("CANCELLED", placed.status());
    }

    // ---------------------------------------------------------------- cancel and modify

    @Test
    void cancellingARestingOrderReturnsWhatWasLeftOfIt() throws Exception {
        start();
        service.placeOrder(gtc("Ravi", Side.SELL, 4000, 10));
        service.placeOrder(gtc("Anu", Side.BUY, 4000, 4)); // Ravi has 6 left

        Domain.CancelledOrder cancelled = service.cancelOrder(1);

        assertEquals(1, cancelled.orderId());
        assertEquals(6, cancelled.cancelledQuantity());
        assertTrue(service.book(5).asks().isEmpty());
    }

    @Test
    void cancellingAnUnknownOrAlreadyCancelledOrFilledOrderIsNotFound() throws Exception {
        start();
        service.placeOrder(gtc("Ravi", Side.SELL, 4000, 10));
        service.cancelOrder(1);
        service.placeOrder(gtc("Priya", Side.SELL, 4000, 5));
        service.placeOrder(gtc("Anu", Side.BUY, 4000, 5)); // fills order 3 completely

        assertThrows(OrderNotFoundException.class, () -> service.cancelOrder(99));  // never existed
        assertThrows(OrderNotFoundException.class, () -> service.cancelOrder(1));   // already cancelled
        OrderNotFoundException filled = assertThrows(OrderNotFoundException.class, () -> service.cancelOrder(3));
        assertEquals(3, filled.orderId());
    }

    @Test
    void shrinkingAnOrderAtTheSamePriceKeepsItsPlaceInTheQueue() throws Exception {
        start();
        service.placeOrder(gtc("Ravi", Side.SELL, 4000, 10));

        Domain.ModifiedOrder modified = service.modifyOrder(1, 4000, 4);

        assertTrue(modified.keptPriority());
        assertTrue(modified.trades().isEmpty());
        assertEquals(4, service.book(5).asks().get(0).totalQuantity());
    }

    @Test
    void changingThePriceLosesThePlaceInTheQueue() throws Exception {
        start();
        service.placeOrder(gtc("Ravi", Side.SELL, 4000, 10));

        Domain.ModifiedOrder modified = service.modifyOrder(1, 4100, 10);

        assertFalse(modified.keptPriority());
        assertEquals(4100, service.book(5).asks().get(0).priceInPaise());
    }

    @Test
    void aModifyThatNowCrossesTheBookTradesImmediately() throws Exception {
        start();
        service.placeOrder(gtc("Ravi", Side.SELL, 4200, 10));
        service.placeOrder(gtc("Anu", Side.BUY, 4000, 10));

        Domain.ModifiedOrder modified = service.modifyOrder(2, 4200, 10);

        assertEquals(1, modified.trades().size());
        assertEquals(4200, modified.trades().get(0).price());
        assertEquals("Anu", modified.trades().get(0).buyer());
    }

    @Test
    void modifyingAnOrderThatIsNotRestingIsNotFound() throws Exception {
        start();

        assertThrows(OrderNotFoundException.class, () -> service.modifyOrder(7, 4000, 5));
    }

    // ---------------------------------------------------------------- reads

    @Test
    void theBookShowsTheBestLevelsFirstWithTotalsAndTheBestPrices() throws Exception {
        start();
        service.placeOrder(gtc("A", Side.BUY, 3900, 5));
        service.placeOrder(gtc("B", Side.BUY, 4000, 7));
        service.placeOrder(gtc("C", Side.BUY, 4000, 3));
        service.placeOrder(gtc("D", Side.SELL, 4200, 2));
        service.placeOrder(gtc("E", Side.SELL, 4100, 6));

        Domain.BookSnapshot book = service.book(10);

        assertEquals(List.of(new OrderBook.Level(4000, 10), new OrderBook.Level(3900, 5)), book.bids());
        assertEquals(List.of(new OrderBook.Level(4100, 6), new OrderBook.Level(4200, 2)), book.asks());
        assertEquals(4000L, book.bestBid());
        assertEquals(4100L, book.bestAsk());
    }

    @Test
    void theBookDepthLimitsHowManyLevelsAreReturned() throws Exception {
        start();
        for (long price = 3900; price <= 3950; price += 10) {
            service.placeOrder(gtc("A", Side.BUY, price, 1));
        }

        assertEquals(2, service.book(2).bids().size());
        assertEquals(6, service.book(100).bids().size());
    }

    @Test
    void anEmptyBookHasNoBestPrices() throws Exception {
        start();

        Domain.BookSnapshot book = service.book(10);

        assertTrue(book.bids().isEmpty());
        assertTrue(book.asks().isEmpty());
        assertNull(book.bestBid());
        assertNull(book.bestAsk());
    }

    @Test
    void recentTradesAreNewestFirstAndRespectTheLimit() throws Exception {
        start();
        service.placeOrder(gtc("Ravi", Side.SELL, 4000, 100));
        for (int i = 0; i < 5; i++) {
            service.placeOrder(gtc("Anu", Side.BUY, 4000, 1));
        }

        List<Trade> all = service.recentTrades(50);
        List<Trade> two = service.recentTrades(2);

        assertEquals(List.of(5L, 4L, 3L, 2L, 1L), all.stream().map(Trade::tradeId).toList());
        assertEquals(List.of(5L, 4L), two.stream().map(Trade::tradeId).toList());
    }

    @Test
    void aTradeIsVisibleInRecentTradesAsSoonAsThePlacingCallReturns() throws Exception {
        start();
        service.placeOrder(gtc("Ravi", Side.SELL, 4000, 10));

        service.placeOrder(gtc("Anu", Side.BUY, 4000, 10));

        assertEquals(1, service.recentTrades(10).size()); // read-your-writes
    }

    // ---------------------------------------------------------------- many HTTP-like threads at once

    @Test
    void manyThreadsPlacingOrdersAtOnceGetUniqueIdsAndSequencesAndNothingIsLost() throws Exception {
        start();
        int threads = 4;
        int perThread = 150;
        Set<Long> orderIds = Collections.synchronizedSet(new HashSet<>());
        Set<Long> sequences = Collections.synchronizedSet(new HashSet<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        Domain.PlacedOrder placed = service.placeOrder(gtc("T", Side.SELL, 4000, 1));
                        orderIds.add(placed.orderId());
                        sequences.add(placed.sequence());
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        assertTrue(errors.isEmpty(), "errors: " + errors);
        assertEquals(600, orderIds.size());
        assertEquals(600, sequences.size());
        assertEquals(600, service.book(1).asks().get(0).totalQuantity());
    }

    // ---------------------------------------------------------------- when the exchange cannot answer

    @Test
    void afterTheServiceIsClosedRequestsAreAnsweredWithUnavailableNotHung() throws Exception {
        start();
        service.close();

        assertThrows(ExchangeUnavailableException.class, () -> service.placeOrder(gtc("A", Side.BUY, 4000, 1)));
        assertThrows(ExchangeUnavailableException.class, () -> service.book(5));
        assertThrows(ExchangeUnavailableException.class, () -> service.recentTrades(5));
    }

    @Test
    void ifTheEngineThreadDoesNotAnswerInTimeTheCallerGetsUnavailableInsteadOfWaitingForever() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        start(Duration.ofMillis(150), (sequence, events) -> {
            try {
                release.await(10, TimeUnit.SECONDS); // the engine thread is stuck "publishing"
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        ExchangeUnavailableException e = assertThrows(ExchangeUnavailableException.class,
                () -> service.placeOrder(gtc("A", Side.BUY, 4000, 1)));

        assertTrue(e.getMessage().contains("did not answer"), e.getMessage());
        release.countDown();
    }

    @Test
    void ifTheSequencerFailsTheCallerIsToldTheExchangeStoppedAndLaterCallsAreUnavailable() throws Exception {
        start(Duration.ofSeconds(10), (sequence, events) -> {
            throw new IllegalStateException("subscriber blew up");
        });

        ExchangeUnavailableException first = assertThrows(ExchangeUnavailableException.class,
                () -> service.placeOrder(gtc("A", Side.BUY, 4000, 1)));
        ExchangeUnavailableException later = assertThrows(ExchangeUnavailableException.class,
                () -> service.placeOrder(gtc("A", Side.BUY, 4000, 1)));

        assertTrue(first.getMessage().contains("subscriber blew up"), first.getMessage());
        assertTrue(later.getMessage().contains("not accepting"), later.getMessage());
    }

    // ---------------------------------------------------------------- everything went through the log

    @Test
    void everyCommandIncludingARefusedOneIsInTheLogWithPricesAsPaise() throws Exception {
        start();
        service.placeOrder(gtc("Ravi", Side.SELL, 4000, 10));
        service.cancelOrder(1);
        assertThrows(OrderNotFoundException.class, () -> service.cancelOrder(1)); // refused, but still logged
        service.book(5);                                                          // a read: NOT logged
        service.recentTrades(5);                                                  // a read: NOT logged
        service.close();

        List<String> lines = Files.readAllLines(dir.resolve("commands.log"));

        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("\"priceInPaise\":4000"), lines.get(0));
        assertTrue(lines.get(1).contains("\"kind\":\"CancelOrder\""), lines.get(1));
    }
}
