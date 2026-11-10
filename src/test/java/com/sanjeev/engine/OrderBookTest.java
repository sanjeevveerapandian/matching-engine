package com.sanjeev.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderBookTest {

    private OrderBook book;
    private long nextId;

    @BeforeEach
    void freshBook() {
        book = new OrderBook();
        nextId = 1;
    }

    /** Adds an order; id and sequence both come from one counter, like arrival order. */
    private Order rest(Side side, long price, long qty) {
        Order order = new Order(nextId, side, price, qty, nextId);
        nextId++;
        book.addResting(order);
        BookInvariants.check(book);
        return order;
    }

    @Test
    void emptyBookHasNoBestBidNoBestAskAndNoSpread() {
        assertNull(book.bestBid());
        assertNull(book.bestAsk());
        assertNull(book.spread());
    }

    @Test
    void bidsAreSortedHighestPriceFirst() {
        rest(Side.BUY, 2900, 10);
        rest(Side.BUY, 3100, 10);
        rest(Side.BUY, 3000, 10);

        assertEquals(3100L, book.bestBid());
    }

    @Test
    void asksAreSortedLowestPriceFirst() {
        rest(Side.SELL, 3300, 10);
        rest(Side.SELL, 3100, 10);
        rest(Side.SELL, 3200, 10);

        assertEquals(3100L, book.bestAsk());
    }

    @Test
    void twoOrdersAtTheSamePriceShareOneLevelInArrivalOrder() {
        Order first = rest(Side.SELL, 3200, 10);
        Order second = rest(Side.SELL, 3200, 5);

        assertEquals(1, book.depth(Side.SELL, 10).size());

        List<Order> inLine = new ArrayList<>();
        book.levelAt(Side.SELL, 3200).orders().forEach(inLine::add);
        assertEquals(List.of(first, second), inLine);
    }

    @Test
    void bestLevelIsTheBestPriceOnThatSideOrNullWhenEmpty() {
        assertNull(book.bestLevel(Side.SELL));

        rest(Side.SELL, 3300, 1);
        rest(Side.SELL, 3100, 1);
        rest(Side.BUY, 2900, 1);
        rest(Side.BUY, 3000, 1);

        assertEquals(3100, book.bestLevel(Side.SELL).getPriceInPaise());
        assertEquals(3000, book.bestLevel(Side.BUY).getPriceInPaise());
    }

    @Test
    void emptyLevelIsRemovedFromTheBookButANonEmptyOneIsKept() {
        rest(Side.SELL, 3100, 10);
        rest(Side.SELL, 3200, 10);

        book.removeLevelIfEmpty(Side.SELL, 3100);
        assertEquals(3100L, book.bestAsk());

        book.bestLevel(Side.SELL).removeFirst();
        book.removeLevelIfEmpty(Side.SELL, 3100);
        assertEquals(3200L, book.bestAsk());
    }

    @Test
    void bookRefusesAMarketOrderBecauseItCannotRest() {
        Order market = Order.market(1, "Anu", Side.BUY, 10, 1);

        assertThrows(IllegalArgumentException.class, () -> book.addResting(market));
        assertEquals(0, book.indexSize());
    }

    @Test
    void bookRefusesASecondRestingOrderWithTheSameId() {
        book.addResting(new Order(7, Side.SELL, 3000, 10, 1));

        assertThrows(IllegalArgumentException.class,
                () -> book.addResting(new Order(7, Side.BUY, 2900, 5, 2)));

        assertEquals(1, book.indexSize());
        assertNull(book.bestBid());
    }

    @Test
    void containsIsTrueOnlyWhileTheOrderIsResting() {
        Order ravi = rest(Side.SELL, 3000, 10);

        assertTrue(book.contains(ravi.getId()));

        book.cancel(ravi.getId());
        BookInvariants.check(book);

        assertFalse(book.contains(ravi.getId()));
    }

    @Test
    void restingOrdersAreAddedToTheIndexAndCountedOnTheBook() {
        rest(Side.SELL, 3000, 10);
        rest(Side.SELL, 3000, 10);
        rest(Side.BUY, 2900, 10);

        assertEquals(3, book.indexSize());
        assertEquals(3, book.restingOrderCount());
    }

    @Test
    void spreadIsBestAskMinusBestBid() {
        rest(Side.BUY, 3000, 10);
        rest(Side.BUY, 3050, 10);
        rest(Side.SELL, 3200, 10);
        rest(Side.SELL, 3150, 10);

        assertEquals(100L, book.spread());
    }

    @Test
    void spreadIsNullWhenOnlyOneSideHasOrders() {
        rest(Side.BUY, 3000, 10);

        assertNull(book.spread());
    }

    @Test
    void depthListsLevelsBestFirstWithCorrectTotals() {
        rest(Side.BUY, 3000, 10);
        rest(Side.BUY, 3100, 4);
        rest(Side.BUY, 3100, 6);
        rest(Side.BUY, 2900, 7);

        List<OrderBook.LevelSummary> bids = book.depth(Side.BUY, 10);

        assertEquals(List.of(
                new OrderBook.LevelSummary(3100, 10),
                new OrderBook.LevelSummary(3000, 10),
                new OrderBook.LevelSummary(2900, 7)), bids);
    }

    @Test
    void askDepthListsLowestPriceFirst() {
        rest(Side.SELL, 3300, 1);
        rest(Side.SELL, 3100, 2);
        rest(Side.SELL, 3200, 3);

        assertEquals(List.of(
                new OrderBook.LevelSummary(3100, 2),
                new OrderBook.LevelSummary(3200, 3),
                new OrderBook.LevelSummary(3300, 1)), book.depth(Side.SELL, 10));
    }

    @Test
    void depthReturnsOnlyTheRequestedNumberOfTopLevels() {
        rest(Side.BUY, 3000, 1);
        rest(Side.BUY, 3100, 1);
        rest(Side.BUY, 2900, 1);

        List<OrderBook.LevelSummary> top2 = book.depth(Side.BUY, 2);

        assertEquals(List.of(new OrderBook.LevelSummary(3100, 1), new OrderBook.LevelSummary(3000, 1)), top2);
    }

    @Test
    void depthOfAnEmptySideIsEmpty() {
        assertTrue(book.depth(Side.BUY, 5).isEmpty());
    }

    @Test
    void askingForNegativeDepthIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> book.depth(Side.BUY, -1));
    }

    @Test
    void addingANullOrderIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> book.addResting(null));
    }

    @Test
    void ladderPrintsAsksHighToLowThenSpreadThenBidsHighToLow() {
        rest(Side.SELL, 3300, 5);
        rest(Side.SELL, 3200, 2);
        rest(Side.BUY, 3100, 7);
        rest(Side.BUY, 3000, 1);

        String[] lines = book.toString().strip().split("\\R");

        assertEquals(5, lines.length);
        assertTrue(lines[0].contains("ASK") && lines[0].contains("3300"));
        assertTrue(lines[1].contains("ASK") && lines[1].contains("3200"));
        assertTrue(lines[2].contains("spread: 100"));
        assertTrue(lines[3].contains("BID") && lines[3].contains("3100"));
        assertTrue(lines[4].contains("BID") && lines[4].contains("3000"));
    }

    @Test
    void ladderOfAnEmptyBookShowsOnlyAnUnknownSpread() {
        assertTrue(book.toString().contains("spread: n/a"));
    }

    @Test
    void ladderWithOnlyOneSideStillPrintsThatSideAndAnUnknownSpread() {
        rest(Side.SELL, 3300, 5);

        String ladder = book.toString();

        assertTrue(ladder.contains("ASK") && ladder.contains("3300"));
        assertFalse(ladder.contains("BID"));
        assertTrue(ladder.contains("spread: n/a"));
    }

    @Test
    void ladderUsesPlainAsciiDigitsWhateverTheMachinesLanguageIs() {
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("th-TH-u-nu-thai")); // Thai digits
            rest(Side.SELL, 3300, 5);

            String ladder = book.toString();

            assertTrue(ladder.matches("(?s)[\\x00-\\x7F]*"), "non-ASCII characters in the ladder: " + ladder);
            assertTrue(ladder.contains("3300 x 5"));
        } finally {
            java.util.Locale.setDefault(original);
        }
    }

    // ---------------------------------------------------------------- the book notices its own corruption

    @Test
    void removingAnOrderThatNoLevelHoldsIsReportedAsCorruptionNotIgnored() {
        Order ghost = new Order(1, Side.SELL, 3000, 10, 1);
        book.rawIndex().put(1L, ghost); // the index claims it rests, but there is no level at 3000

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> book.removeResting(ghost));

        assertTrue(e.getMessage().contains("out of sync"), e.getMessage());
    }

    @Test
    void removingAnOrderThatItsLevelDoesNotHoldIsReportedAsCorruption() {
        rest(Side.SELL, 3000, 10);                      // a real level at 3000 exists
        Order stranger = new Order(99, Side.SELL, 3000, 5, 99);
        book.rawIndex().put(99L, stranger);             // indexed, but not in that level's queue

        assertThrows(IllegalStateException.class, () -> book.cancel(99));
    }

    @Test
    void aLevelThatStillHasOrdersIsNotRemovedAndAPriceWithNoLevelIsIgnored() {
        rest(Side.SELL, 3000, 10);

        book.removeLevelIfEmpty(Side.SELL, 3000); // still has an order: stays
        book.removeLevelIfEmpty(Side.SELL, 9999); // no such level: nothing happens

        assertEquals(10, book.depth(Side.SELL, 5).get(0).totalQuantity());
        assertEquals(1, book.depth(Side.SELL, 5).size());
    }

    @Test
    void theLevelsBestFirstViewKnowsHowManyLevelsThereAre() {
        rest(Side.SELL, 3000, 1);
        rest(Side.SELL, 3100, 1);
        rest(Side.SELL, 3100, 1);

        assertEquals(2, book.levelsBestFirst(Side.SELL).size());
        assertEquals(0, book.levelsBestFirst(Side.BUY).size());
    }

    // ---------------------------------------------------------------- only GTC orders with something left may rest

    @Test
    void anImmediateOrCancelOrderIsNeverAllowedToRest() {
        Order ioc = Order.limit(1, "Ravi", Side.SELL, 3000, 10, 1, TimeInForce.IOC);

        assertThrows(IllegalArgumentException.class, () -> book.addResting(ioc));
        assertFalse(book.contains(1));
        assertEquals(0, book.restingOrderCount());
    }

    @Test
    void aFillOrKillOrderIsNeverAllowedToRest() {
        Order fok = Order.limit(1, "Ravi", Side.SELL, 3000, 10, 1, TimeInForce.FOK);

        assertThrows(IllegalArgumentException.class, () -> book.addResting(fok));
        assertFalse(book.contains(1));
    }

    @Test
    void anOrderWithNothingLeftToTradeIsNeverAllowedToRest() {
        Order finished = new Order(1, Side.SELL, 3000, 10, 1);
        finished.fill(10);

        assertThrows(IllegalArgumentException.class, () -> book.addResting(finished));
        assertFalse(book.contains(1));
        assertEquals(0, book.restingOrderCount());
    }
}
