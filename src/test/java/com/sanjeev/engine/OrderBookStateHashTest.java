package com.sanjeev.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OrderBookStateHashTest {

    private static Order order(long id, Side side, long price, long qty, long sequence) {
        return new Order(id, side, price, qty, sequence);
    }

    private static String hashOf(Order... orders) {
        OrderBook book = new OrderBook();
        for (Order order : orders) {
            book.addResting(order);
        }
        return book.stateHash();
    }

    // ---------------------------------------------------------------- the encoding, checked independently

    /** Expected values were computed by a separate PowerShell/.NET script from the documented byte layout. */
    @Test
    void anEmptyBookHasTheHashOfTwoZeroCounts() {
        assertEquals("374708fff7719dd5979ec875d56cd2286f6d3cf7ec317a3b25632aab28ec37bb",
                new OrderBook().stateHash());
    }

    @Test
    void aSmallBookHasExactlyTheHashTheDocumentedByteLayoutGives() {
        Order partlyFilled = order(2, Side.SELL, 3000, 10, 2);
        partlyFilled.fill(3); // 7 remaining

        String hash = hashOf(
                order(1, Side.BUY, 2900, 10, 1),
                partlyFilled,
                order(3, Side.SELL, 3100, 4, 3));

        assertEquals("490e40a745dab62e71dc93591d5c953c12de97bfbecca87efbd53ca789b6c7f8", hash);
    }

    @Test
    void theHashIs64LowercaseHexCharacters() {
        assertTrue(hashOf(order(1, Side.BUY, 2900, 10, 1)).matches("[0-9a-f]{64}"));
    }

    // ---------------------------------------------------------------- what the hash must notice

    @Test
    void twoBooksWithTheSameOrdersHaveTheSameHash() {
        assertEquals(
                hashOf(order(1, Side.BUY, 2900, 10, 1), order(2, Side.SELL, 3000, 5, 2)),
                hashOf(order(1, Side.BUY, 2900, 10, 1), order(2, Side.SELL, 3000, 5, 2)));
    }

    @Test
    void theOrderInWhichOrdersWereAddedAcrossDifferentPricesDoesNotMatter() {
        assertEquals(
                hashOf(order(1, Side.BUY, 2900, 10, 1), order(2, Side.BUY, 2800, 5, 2)),
                hashOf(order(2, Side.BUY, 2800, 5, 2), order(1, Side.BUY, 2900, 10, 1)));
    }

    @Test
    void historyDoesNotMatterOnlyTheStateDoes() {
        OrderBook withCancelledOrder = new OrderBook();
        withCancelledOrder.addResting(order(1, Side.SELL, 3000, 10, 1));
        withCancelledOrder.addResting(order(2, Side.SELL, 3000, 5, 2));
        withCancelledOrder.addResting(order(3, Side.SELL, 3000, 7, 3));
        withCancelledOrder.cancel(2);

        OrderBook neverHadIt = new OrderBook();
        neverHadIt.addResting(order(1, Side.SELL, 3000, 10, 1));
        neverHadIt.addResting(order(3, Side.SELL, 3000, 7, 3));

        assertEquals(neverHadIt.stateHash(), withCancelledOrder.stateHash());
    }

    @Test
    void changingAnyOneFieldChangesTheHash() {
        String base = hashOf(order(1, Side.BUY, 2900, 10, 1));

        assertNotEquals(base, hashOf(order(9, Side.BUY, 2900, 10, 1)), "id");
        assertNotEquals(base, hashOf(order(1, Side.SELL, 2900, 10, 1)), "side");
        assertNotEquals(base, hashOf(order(1, Side.BUY, 2901, 10, 1)), "price");
        assertNotEquals(base, hashOf(order(1, Side.BUY, 2900, 11, 1)), "remaining quantity");
        assertNotEquals(base, hashOf(order(1, Side.BUY, 2900, 10, 2)), "sequence");
    }

    @Test
    void aPartialFillChangesTheHash() {
        Order order = order(1, Side.SELL, 3000, 10, 1);
        OrderBook book = new OrderBook();
        book.addResting(order);
        String before = book.stateHash();

        order.fill(4);

        assertNotEquals(before, book.stateHash());
    }

    @Test
    void theQueueOrderInsideAPriceLevelChangesTheHash() {
        String firstThenSecond = hashOf(order(1, Side.SELL, 3000, 10, 1), order(2, Side.SELL, 3000, 10, 2));
        String secondThenFirst = hashOf(order(2, Side.SELL, 3000, 10, 2), order(1, Side.SELL, 3000, 10, 1));

        assertNotEquals(firstThenSecond, secondThenFirst);
    }

    @Test
    void anOrderMovedFromTheBidSideToTheAskSideChangesTheHash() {
        assertNotEquals(hashOf(order(1, Side.BUY, 2900, 10, 1), order(2, Side.BUY, 2800, 5, 2)),
                hashOf(order(1, Side.BUY, 2900, 10, 1), order(2, Side.SELL, 2800, 5, 2)));
    }

    @Test
    void hashingDoesNotChangeTheBook() {
        OrderBook book = new OrderBook();
        book.addResting(order(1, Side.BUY, 2900, 10, 1));

        assertEquals(book.stateHash(), book.stateHash());
        assertEquals(1, book.restingOrderCount());
    }
}
