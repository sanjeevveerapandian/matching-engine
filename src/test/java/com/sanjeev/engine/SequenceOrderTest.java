package com.sanjeev.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Found by the seeded simulation (seed 42, operation #75): a re-submitted order from
 * modify() got the engine's next sequence number, and the caller's next order was numbered
 * with that same value, so two orders at one price shared a sequence number.
 */
class SequenceOrderTest {

    @Test
    void anOrderThatWouldShareASequenceNumberWithAQueuedOrderAtItsPriceIsRejected() {
        CheckedEngine engine = new CheckedEngine();
        engine.submitLimit(new Order(1, "Ravi", Side.SELL, 3000, 10, 1));
        engine.modify(1, 3000, 20); // quantity up: Ravi is re-submitted, taking sequence 2

        Order priya = new Order(2, "Priya", Side.SELL, 3000, 5, 2); // the caller also used 2

        assertThrows(IllegalArgumentException.class, () -> engine.submitLimit(priya));
        assertEquals(1, engine.book().indexSize()); // only Ravi is on the book
    }

    @Test
    void sequenceNumbersFromTheEngineKeepEveryQueueInArrivalOrderEvenAfterAModify() {
        CheckedEngine engine = new CheckedEngine();
        Order ravi = new Order(1, "Ravi", Side.SELL, 3000, 10, engine.nextSequence());
        engine.submitLimit(ravi);
        engine.modify(1, 3000, 20); // Ravi goes to the back, with a newer number
        Order priya = new Order(2, "Priya", Side.SELL, 3000, 5, engine.nextSequence());

        engine.submitLimit(priya);

        List<Order> queue = new ArrayList<>();
        engine.book().levelAt(Side.SELL, 3000).orders().forEach(queue::add);
        assertEquals(List.of(1L, 2L), queue.stream().map(Order::getId).toList());
        assertTrue(queue.get(0).getSequence() < queue.get(1).getSequence());
    }

    @Test
    void nextSequenceGivesStrictlyIncreasingNumbers() {
        CheckedEngine engine = new CheckedEngine();

        long first = engine.nextSequence();
        long second = engine.nextSequence();

        assertEquals(first + 1, second);
    }

    @Test
    void anOrderWithAnOlderSequenceThanTheNewestAtItsPriceIsRejectedToo() {
        CheckedEngine engine = new CheckedEngine();
        engine.submitLimit(new Order(1, "Ravi", Side.SELL, 3000, 10, 5));

        assertThrows(IllegalArgumentException.class,
                () -> engine.submitLimit(new Order(2, "Priya", Side.SELL, 3000, 5, 3)));
    }

    @Test
    void theSameSequenceNumberAtADifferentPriceIsFine() {
        CheckedEngine engine = new CheckedEngine();
        engine.submitLimit(new Order(1, "Ravi", Side.SELL, 3000, 10, 1));

        engine.submitLimit(new Order(2, "Priya", Side.SELL, 3100, 5, 1)); // other level: no clash

        assertEquals(2, engine.book().indexSize());
    }

    @Test
    void anOrderThatCannotRestIsNotHeldToTheQueueRule() {
        CheckedEngine engine = new CheckedEngine();
        engine.submitLimit(new Order(1, "Ravi", Side.SELL, 3000, 10, 5));

        List<Trade> trades = engine.submitLimit(
                Order.limit(2, "Priya", Side.SELL, 3000, 5, 1, TimeInForce.IOC)); // never rests

        assertTrue(trades.isEmpty());
        assertEquals(1, engine.book().indexSize());
    }

    @Test
    void anOldArrivalNumberDoesNotStopAnOrderThatTradesCompletelyBecauseItNeverQueues() {
        CheckedEngine engine = new CheckedEngine();
        engine.submitLimit(new Order(1, "Ravi", Side.SELL, 3000, 10, 5));

        // sequence 2 is older than everything on the book, but this buy never queues: it trades fully
        List<Trade> trades = engine.submitLimit(new Order(2, "Anu", Side.BUY, 3000, 4, 2));

        assertEquals(1, trades.size());
        assertEquals(4, trades.get(0).quantity());
        assertEquals(1, engine.book().indexSize()); // Ravi, with 6 left
    }
}
