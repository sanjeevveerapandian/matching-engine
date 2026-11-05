package com.sanjeev.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        List<OrderBook.Level> bids = book.depth(Side.BUY, 10);

        assertEquals(List.of(
                new OrderBook.Level(3100, 10),
                new OrderBook.Level(3000, 10),
                new OrderBook.Level(2900, 7)), bids);
    }

    @Test
    void askDepthListsLowestPriceFirst() {
        rest(Side.SELL, 3300, 1);
        rest(Side.SELL, 3100, 2);
        rest(Side.SELL, 3200, 3);

        assertEquals(List.of(
                new OrderBook.Level(3100, 2),
                new OrderBook.Level(3200, 3),
                new OrderBook.Level(3300, 1)), book.depth(Side.SELL, 10));
    }

    @Test
    void depthReturnsOnlyTheRequestedNumberOfTopLevels() {
        rest(Side.BUY, 3000, 1);
        rest(Side.BUY, 3100, 1);
        rest(Side.BUY, 2900, 1);

        List<OrderBook.Level> top2 = book.depth(Side.BUY, 2);

        assertEquals(List.of(new OrderBook.Level(3100, 1), new OrderBook.Level(3000, 1)), top2);
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
}
