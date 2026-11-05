package com.sanjeev.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * For each rule: build a book that breaks ONLY that rule (by reaching into the book's
 * package-private internals, which normal code cannot do) and expect the checker to throw.
 */
class BookInvariantsTest {

    private OrderBook book;

    @BeforeEach
    void freshBook() {
        book = new OrderBook();
    }

    private static Order order(long id, Side side, long price, long qty, long sequence) {
        return new Order(id, side, price, qty, sequence);
    }

    private void assertBroken(String expectedWords) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BookInvariants.check(book));
        assertTrue(e.getMessage().contains(expectedWords),
                "expected message to mention '" + expectedWords + "' but was: " + e.getMessage());
    }

    // ---------------------------------------------------------------- healthy books

    @Test
    void emptyBookPasses() {
        assertDoesNotThrow(() -> BookInvariants.check(book));
    }

    @Test
    void normalBookWithBothSidesAndSeveralOrdersPerLevelPasses() {
        book.addResting(order(1, Side.SELL, 3200, 10, 1));
        book.addResting(order(2, Side.SELL, 3200, 5, 2));
        book.addResting(order(3, Side.SELL, 3300, 5, 3));
        book.addResting(order(4, Side.BUY, 3100, 7, 4));
        book.addResting(order(5, Side.BUY, 3000, 7, 5));

        assertDoesNotThrow(() -> BookInvariants.check(book));
    }

    @Test
    void aBookWhereBestBidAndBestAskAreDifferentPricesIsNotCrossed() {
        book.addResting(order(1, Side.SELL, 3100, 10, 1));
        book.addResting(order(2, Side.BUY, 3099, 10, 2));

        assertDoesNotThrow(() -> BookInvariants.check(book));
    }

    // ---------------------------------------------------------------- one test per rule

    @Test
    void crossedBookIsDetected() {
        book.addResting(order(1, Side.SELL, 3000, 10, 1));
        book.addResting(order(2, Side.BUY, 3100, 10, 2)); // addResting does not match

        assertBroken("crossed");
    }

    @Test
    void bookWhereBestBidEqualsBestAskIsAlsoCrossed() {
        book.addResting(order(1, Side.SELL, 3000, 10, 1));
        book.addResting(order(2, Side.BUY, 3000, 10, 2));

        assertBroken("crossed");
    }

    @Test
    void emptyPriceLevelIsDetected() {
        book.addResting(order(1, Side.SELL, 3100, 10, 1));
        book.levelsOf(Side.SELL).put(3000L, new PriceLevel(3000)); // a level nobody stands in

        assertBroken("empty price level");
    }

    @Test
    void restingOrderWithNothingLeftToTradeIsDetected() {
        Order ravi = order(1, Side.SELL, 3000, 10, 1);
        book.addResting(ravi);
        ravi.fill(10); // filled, but nobody removed it from the book

        assertBroken("remaining quantity");
    }

    @Test
    void orderSittingInALevelWithADifferentPriceIsDetected() {
        Order ravi = order(1, Side.SELL, 3000, 10, 1);
        book.addResting(ravi);
        PriceLevel level = book.levelsOf(Side.SELL).remove(3000L);
        book.levelsOf(Side.SELL).put(3100L, level); // a 3000 order filed under 3100

        assertBroken("level priced");
    }

    @Test
    void indexWithADifferentCountThanTheBookIsDetected() {
        Order ravi = order(1, Side.SELL, 3000, 10, 1);
        book.addResting(ravi);
        book.rawIndex().remove(1L); // the order is on the book but forgotten by the index

        assertBroken("index holds");
    }

    @Test
    void indexWithTheSameCountButDifferentOrdersIsDetected() {
        book.addResting(order(1, Side.SELL, 3000, 10, 1));
        book.addResting(order(2, Side.SELL, 3100, 10, 2));
        book.rawIndex().put(2L, order(2, Side.SELL, 3100, 10, 2)); // a look-alike, not the real one

        assertBroken("index does not hold");
    }

    @Test
    void ordersInALevelOutOfSequenceOrderAreDetected() {
        book.addResting(order(1, Side.SELL, 3000, 10, 5));
        book.addResting(order(2, Side.SELL, 3000, 10, 3)); // arrived "earlier" but queued later

        assertBroken("sequence");
    }

    @Test
    void twoOrdersWithTheSameSequenceInALevelAreDetected() {
        book.addResting(order(1, Side.SELL, 3000, 10, 4));
        book.addResting(order(2, Side.SELL, 3000, 10, 4));

        assertBroken("sequence");
    }
}
