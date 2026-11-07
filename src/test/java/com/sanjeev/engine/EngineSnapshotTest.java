package com.sanjeev.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EngineSnapshotTest {

    private static Order limit(MatchingEngine engine, long id, String trader, Side side, long price, long qty) {
        return Order.limit(id, trader, side, price, qty, engine.nextSequence(), TimeInForce.GTC);
    }

    /** An engine with some history: resting orders on both sides, a partial fill, and 4 trades done. */
    private static MatchingEngine busyEngine() {
        MatchingEngine engine = new MatchingEngine();
        engine.processLimit(limit(engine, 1, "Ravi", Side.SELL, 3000, 50));
        engine.processLimit(limit(engine, 2, "Priya", Side.SELL, 3000, 20));
        engine.processLimit(limit(engine, 3, "Kumar", Side.SELL, 3100, 30));
        engine.processLimit(limit(engine, 4, "Meena", Side.BUY, 2900, 40));
        engine.processLimit(limit(engine, 5, "Anu", Side.BUY, 2950, 15));
        engine.processLimit(limit(engine, 6, "Anu", Side.BUY, 3000, 20));  // trades with Ravi
        engine.processLimit(limit(engine, 7, "Meena", Side.BUY, 3100, 60)); // trades with Ravi, Priya, Kumar
        return engine;
    }

    @Test
    void aRestoredEngineHasTheSameBookFingerprint() {
        MatchingEngine original = busyEngine();

        MatchingEngine restored = MatchingEngine.restore(original.snapshot());

        assertEquals(original.book().stateHash(), restored.book().stateHash());
        assertEquals(original.book().toString(), restored.book().toString());
    }

    @Test
    void aRestoredEngineKeepsTradersQuantitiesAndQueueOrder() {
        MatchingEngine original = new MatchingEngine();
        original.processLimit(limit(original, 1, "Ravi", Side.SELL, 3000, 50));
        original.processLimit(limit(original, 2, "Priya", Side.SELL, 3000, 20));
        original.processLimit(limit(original, 3, "Anu", Side.BUY, 3000, 20)); // Ravi has 30 left of 50

        MatchingEngine restored = MatchingEngine.restore(original.snapshot());

        List<Order> queue = new ArrayList<>();
        restored.book().levelAt(Side.SELL, 3000).orders().forEach(queue::add);
        assertEquals(List.of(1L, 2L), queue.stream().map(Order::getId).toList());
        assertEquals("Ravi", queue.get(0).getTrader());
        assertEquals(30, queue.get(0).getRemainingQuantity());
        assertEquals(50, queue.get(0).getOriginalQuantity());
        assertEquals("Priya", queue.get(1).getTrader());
    }

    @Test
    void aRestoredEngineContinuesTradeIdsWhereTheOriginalLeftOff() {
        MatchingEngine original = busyEngine();
        MatchingEngine restored = MatchingEngine.restore(original.snapshot());

        List<Event> fromOriginal = original.processLimit(limit(original, 8, "Ravi", Side.SELL, 2900, 100));
        List<Event> fromRestored = restored.processLimit(limit(restored, 8, "Ravi", Side.SELL, 2900, 100));

        assertTrue(fromOriginal.stream().anyMatch(e -> e instanceof Event.TradeExecuted));
        assertEquals(fromOriginal, fromRestored); // same trades, same ids, same names
    }

    @Test
    void aRestoredEngineHandsOutArrivalNumbersAfterTheOnesAlreadyUsed() {
        MatchingEngine original = busyEngine();
        MatchingEngine restored = MatchingEngine.restore(original.snapshot());

        assertEquals(original.nextSequence(), restored.nextSequence());
    }

    @Test
    void anOriginalAndItsRestoredCopyStayIdenticalThroughALongSeriesOfOperations() {
        MatchingEngine original = busyEngine();
        MatchingEngine restored = MatchingEngine.restore(original.snapshot());
        List<Event> eventsOriginal = new ArrayList<>();
        List<Event> eventsRestored = new ArrayList<>();

        for (int i = 0; i < 200; i++) {
            long id = 100 + i;
            Side side = i % 2 == 0 ? Side.BUY : Side.SELL;
            long price = 2900 + (i * 7L) % 250;
            eventsOriginal.addAll(original.processLimit(limit(original, id, "T" + (i % 3), side, price, 1 + i % 40)));
            eventsRestored.addAll(restored.processLimit(limit(restored, id, "T" + (i % 3), side, price, 1 + i % 40)));
            if (i % 5 == 0) {
                eventsOriginal.addAll(original.processModify(1 + i % 7, 2950 + i % 100, 5 + i % 30));
                eventsRestored.addAll(restored.processModify(1 + i % 7, 2950 + i % 100, 5 + i % 30));
            }
            if (i % 7 == 0) {
                eventsOriginal.addAll(original.processCancel(1 + i % 9));
                eventsRestored.addAll(restored.processCancel(1 + i % 9));
            }
        }

        assertEquals(eventsOriginal, eventsRestored);
        assertEquals(original.book().stateHash(), restored.book().stateHash());
    }

    @Test
    void anEmptyEngineSnapshotsAndRestoresToAnEmptyEngine() {
        MatchingEngine restored = MatchingEngine.restore(new MatchingEngine().snapshot());

        assertEquals(0, restored.book().restingOrderCount());
        assertEquals(new OrderBook().stateHash(), restored.book().stateHash());
        assertEquals(1, restored.nextSequence());
    }

    @Test
    void theSnapshotListsOrdersInBookOrderBidsBestFirstThenAsksBestFirstOldestFirst() {
        MatchingEngine engine = new MatchingEngine();
        engine.processLimit(limit(engine, 1, "A", Side.BUY, 2800, 5));
        engine.processLimit(limit(engine, 2, "B", Side.BUY, 2900, 5));
        engine.processLimit(limit(engine, 3, "C", Side.BUY, 2900, 5));
        engine.processLimit(limit(engine, 4, "D", Side.SELL, 3200, 5));
        engine.processLimit(limit(engine, 5, "E", Side.SELL, 3100, 5));
        engine.processLimit(limit(engine, 6, "F", Side.SELL, 3100, 5));

        List<Long> ids = engine.snapshot().orders().stream().map(EngineSnapshot.OrderState::id).toList();

        assertEquals(List.of(2L, 3L, 1L, 5L, 6L, 4L), ids);
    }

    @Test
    void aSnapshotIsAFrozenValueThatLaterTradingDoesNotChange() {
        MatchingEngine engine = busyEngine();
        EngineSnapshot snapshot = engine.snapshot();
        int ordersThen = snapshot.orders().size();
        long nextTradeThen = snapshot.nextTradeId();

        engine.processLimit(limit(engine, 50, "X", Side.BUY, 3200, 500));

        assertEquals(ordersThen, snapshot.orders().size());
        assertEquals(nextTradeThen, snapshot.nextTradeId());
    }

    // ---------------------------------------------------------------- bad snapshots are refused

    private static EngineSnapshot.OrderState state(long id, long price, long original, long remaining, long sequence) {
        return new EngineSnapshot.OrderState(id, "Ravi", Side.SELL, price, original, remaining, sequence);
    }

    @Test
    void restoringANullSnapshotIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MatchingEngine.restore(null));
    }

    @Test
    void restoringASnapshotWithATradeIdBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MatchingEngine.restore(new EngineSnapshot(0, 5, List.of())));
    }

    @Test
    void restoringAnOrderWithNothingRemainingOrMoreThanItsOriginalIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MatchingEngine.restore(
                new EngineSnapshot(1, 5, List.of(state(1, 3000, 10, 0, 1)))));
        assertThrows(IllegalArgumentException.class, () -> MatchingEngine.restore(
                new EngineSnapshot(1, 5, List.of(state(1, 3000, 10, 11, 1)))));
    }

    @Test
    void restoringTwoOrdersWithTheSameIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MatchingEngine.restore(
                new EngineSnapshot(1, 5, List.of(state(1, 3000, 10, 10, 1), state(1, 3100, 10, 10, 2)))));
    }

    @Test
    void restoringAnOrderNewerThanTheEnginesLastSequenceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MatchingEngine.restore(
                new EngineSnapshot(1, 3, List.of(state(1, 3000, 10, 10, 4)))));
    }
}
