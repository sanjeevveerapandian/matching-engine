package com.sanjeev.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EngineEventsTest {

    private MatchingEngine engine;
    private OrderBook book;
    private long nextId;

    @BeforeEach
    void freshEngine() {
        engine = new MatchingEngine();
        book = engine.book();
        nextId = 1;
    }

    private Order limit(String trader, Side side, long price, long qty, TimeInForce tif) {
        return Order.limit(nextId++, trader, side, price, qty, engine.nextSequence(), tif);
    }

    private List<Event> restLimit(String trader, Side side, long price, long qty) {
        return engine.processLimit(limit(trader, side, price, qty, TimeInForce.GTC));
    }

    private static List<Trade> tradesIn(List<Event> events) {
        List<Trade> trades = new ArrayList<>();
        for (Event event : events) {
            if (event instanceof Event.TradeExecuted t) {
                trades.add(t.trade());
            }
        }
        return trades;
    }

    // ---------------------------------------------------------------- new orders

    @Test
    void aLimitOrderThatRestsProducesOnlyAnAcceptedEvent() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10, TimeInForce.GTC);

        List<Event> events = engine.processLimit(ravi);

        assertEquals(List.of(new Event.OrderAccepted(ravi.getId(), "Ravi", Side.SELL, OrderType.LIMIT,
                TimeInForce.GTC, 3000L, 10, ravi.getSequence())), events);
        assertTrue(book.contains(ravi.getId()));
    }

    @Test
    void aLimitOrderThatTradesProducesAcceptedThenOneTradeEventPerTradeInOrder() {
        restLimit("Ravi", Side.SELL, 3000, 10);
        restLimit("Priya", Side.SELL, 3100, 10);

        List<Event> events = engine.processLimit(limit("Anu", Side.BUY, 3100, 15, TimeInForce.GTC));

        assertEquals(3, events.size());
        assertTrue(events.get(0) instanceof Event.OrderAccepted);
        List<Trade> trades = tradesIn(events);
        assertEquals(2, trades.size());
        assertEquals("Ravi", trades.get(0).seller());
        assertEquals(10, trades.get(0).quantity());
        assertEquals("Priya", trades.get(1).seller());
        assertEquals(5, trades.get(1).quantity());
    }

    @Test
    void anIocOrderThatPartlyFillsEndsWithACancelOfTheUnfilledRemainder() {
        restLimit("Ravi", Side.SELL, 3000, 10);

        List<Event> events = engine.processLimit(limit("Anu", Side.BUY, 3000, 25, TimeInForce.IOC));

        assertEquals(3, events.size());
        assertTrue(events.get(1) instanceof Event.TradeExecuted);
        assertEquals(new Event.OrderCancelled(2, 15, CancelReason.UNFILLED_REMAINDER), events.get(2));
        assertFalse(book.contains(2));
    }

    @Test
    void aFokOrderThatCannotBeFilledIsAcceptedThenCancelledWholeWithNoTrades() {
        restLimit("Ravi", Side.SELL, 3000, 10);

        List<Event> events = engine.processLimit(limit("Anu", Side.BUY, 3000, 25, TimeInForce.FOK));

        assertEquals(2, events.size());
        assertTrue(events.get(0) instanceof Event.OrderAccepted);
        assertEquals(new Event.OrderCancelled(2, 25, CancelReason.FOK_NOT_FILLABLE), events.get(1));
        assertTrue(tradesIn(events).isEmpty());
    }

    @Test
    void aGtcLeftoverThatRestsDoesNotProduceACancelEvent() {
        restLimit("Ravi", Side.SELL, 3000, 10);

        List<Event> events = restLimit("Anu", Side.BUY, 3000, 25);

        assertEquals(2, events.size()); // accepted + one trade; the 15 left simply rests
        assertTrue(book.contains(2));
    }

    @Test
    void aMarketOrderIsAcceptedWithNoPriceAndImmediateOrCancelTerms() {
        Order anu = Order.market(nextId++, "Anu", Side.BUY, 5, engine.nextSequence());

        List<Event> events = engine.processMarket(anu);

        Event.OrderAccepted accepted = (Event.OrderAccepted) events.get(0);
        assertNull(accepted.priceInPaise());
        assertEquals(OrderType.MARKET, accepted.type());
        assertEquals(TimeInForce.IOC, accepted.timeInForce());
    }

    @Test
    void aMarketOrderOnAnEmptyBookIsAcceptedThenCancelledWithItsWholeQuantity() {
        Order anu = Order.market(nextId++, "Anu", Side.BUY, 5, engine.nextSequence());

        List<Event> events = engine.processMarket(anu);

        assertEquals(2, events.size());
        assertEquals(new Event.OrderCancelled(1, 5, CancelReason.UNFILLED_REMAINDER), events.get(1));
    }

    // ---------------------------------------------------------------- rejections

    @Test
    void aDuplicateIdIsRejectedAsAnEventAndNothingChanges() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10, TimeInForce.GTC);
        engine.processLimit(ravi);
        Order again = Order.limit(ravi.getId(), "Anu", Side.BUY, 3000, 10, engine.nextSequence(),
                TimeInForce.GTC);

        List<Event> events = engine.processLimit(again);

        assertEquals(1, events.size());
        Event.OrderRejected rejected = (Event.OrderRejected) events.get(0);
        assertEquals(ravi.getId(), rejected.orderId());
        assertTrue(rejected.reason().contains("duplicate"));
        assertEquals(10, ravi.getRemainingQuantity());
    }

    @Test
    void anOrderWithAStaleSequenceIsRejectedAsAnEvent() {
        engine.processLimit(new Order(1, "Ravi", Side.SELL, 3000, 10, 5));

        List<Event> events = engine.processLimit(new Order(2, "Priya", Side.SELL, 3000, 5, 3));

        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof Event.OrderRejected);
    }

    @Test
    void aMarketOrderSentToProcessLimitIsRejectedNotThrown() {
        Order anu = Order.market(nextId++, "Anu", Side.BUY, 5, engine.nextSequence());

        List<Event> events = engine.processLimit(anu);

        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof Event.OrderRejected);
    }

    @Test
    void aNullOrderIsRejectedNotThrown() {
        List<Event> events = engine.processLimit(null);

        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof Event.OrderRejected);
    }

    // ---------------------------------------------------------------- cancel

    @Test
    void cancellingARestingOrderProducesOrderCancelledWithTheRemainingQuantity() {
        restLimit("Ravi", Side.SELL, 3000, 50);
        restLimit("Anu", Side.BUY, 3000, 20); // Ravi has 30 left

        List<Event> events = engine.processCancel(1);

        assertEquals(List.of(new Event.OrderCancelled(1, 30, CancelReason.REQUESTED)), events);
        assertFalse(book.contains(1));
    }

    @Test
    void cancellingAnOrderThatIsNotRestingIsRejected() {
        List<Event> events = engine.processCancel(99);

        assertEquals(1, events.size());
        assertEquals(99, ((Event.OrderRejected) events.get(0)).orderId());
    }

    // ---------------------------------------------------------------- modify

    @Test
    void aSizeReductionProducesOrderModifiedThatKeptPriority() {
        restLimit("Ravi", Side.SELL, 3000, 10);

        List<Event> events = engine.processModify(1, 3000, 4);

        assertEquals(List.of(new Event.OrderModified(1, 3000, 4, true)), events);
    }

    @Test
    void aPriceChangeProducesOrderModifiedThatLostPriority() {
        restLimit("Ravi", Side.SELL, 3000, 10);

        List<Event> events = engine.processModify(1, 3100, 10);

        assertEquals(List.of(new Event.OrderModified(1, 3100, 10, false)), events);
    }

    @Test
    void aModifyThatNowCrossesProducesOrderModifiedThenTheTrades() {
        restLimit("Ravi", Side.SELL, 3200, 10);
        restLimit("Anu", Side.BUY, 3000, 10);

        List<Event> events = engine.processModify(2, 3200, 10);

        assertEquals(2, events.size());
        assertEquals(new Event.OrderModified(2, 3200, 10, false), events.get(0));
        assertEquals(3200, tradesIn(events).get(0).price());
    }

    @Test
    void modifyingAnOrderThatIsNotRestingIsRejected() {
        List<Event> events = engine.processModify(99, 3000, 5);

        assertTrue(events.get(0) instanceof Event.OrderRejected);
    }

    @Test
    void anInvalidModifyIsRejectedAndLeavesTheOrderExactlyAsItWas() {
        restLimit("Ravi", Side.SELL, 3000, 10);

        List<Event> events = engine.processModify(1, 0, 10);

        assertTrue(events.get(0) instanceof Event.OrderRejected);
        assertEquals(10, book.restingOrder(1).getRemainingQuantity());
        assertEquals(3000, book.restingOrder(1).getPriceInPaise());
    }

    // ---------------------------------------------------------------- consistency with the trade API

    @Test
    void theTradesInTheEventsAreExactlyTheTradesTheOriginalMethodsReturn() {
        MatchingEngine twin = new MatchingEngine();
        List<Trade> fromOldApi = new ArrayList<>();
        List<Trade> fromEvents = new ArrayList<>();
        long[][] script = { // side(0 buy,1 sell), price, qty
                {1, 3000, 10}, {1, 3100, 10}, {0, 3050, 4}, {0, 3200, 30}, {1, 2900, 15}};

        for (int i = 0; i < script.length; i++) {
            Side side = script[i][0] == 0 ? Side.BUY : Side.SELL;
            fromOldApi.addAll(twin.submitLimit(new Order(i + 1, "T", side, script[i][1], script[i][2],
                    twin.nextSequence())));
            fromEvents.addAll(tradesIn(engine.processLimit(new Order(i + 1, "T", side, script[i][1],
                    script[i][2], engine.nextSequence()))));
        }

        assertFalse(fromOldApi.isEmpty());
        assertEquals(fromOldApi, fromEvents);
    }
}
