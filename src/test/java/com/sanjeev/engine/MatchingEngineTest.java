package com.sanjeev.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchingEngineTest {

    private MatchingEngine engine;
    private OrderBook book;
    private long nextId;

    @BeforeEach
    void freshEngine() {
        engine = new MatchingEngine();
        book = engine.book();
        nextId = 1;
    }

    /** Builds an order and submits it. Id and sequence come from one counter (arrival order). */
    private List<Trade> submit(String trader, Side side, long price, long qty) {
        Order order = new Order(nextId, trader, side, price, qty, nextId);
        nextId++;
        return engine.submitLimit(order);
    }

    private long quantityAt(Side side, long price) {
        return book.depth(side, 100).stream()
                .filter(level -> level.priceInPaise() == price)
                .mapToLong(OrderBook.Level::totalQuantity)
                .sum();
    }

    // ---------------------------------------------------------------- incoming BUY

    @Test
    void buyBelowBestAskDoesNotTradeAndBothOrdersRest() {
        submit("Ravi", Side.SELL, 3000, 10);

        List<Trade> trades = submit("Anu", Side.BUY, 2900, 10);

        assertTrue(trades.isEmpty());
        assertEquals(3000L, book.bestAsk());
        assertEquals(2900L, book.bestBid());
    }

    @Test
    void buyThatExactlyMatchesAskTradesFullyAndLeavesBookEmpty() {
        submit("Ravi", Side.SELL, 3000, 10);

        List<Trade> trades = submit("Anu", Side.BUY, 3000, 10);

        assertEquals(1, trades.size());
        Trade trade = trades.get(0);
        assertEquals("Anu", trade.buyer());
        assertEquals("Ravi", trade.seller());
        assertEquals(3000, trade.price());
        assertEquals(10, trade.quantity());
        assertNull(book.bestBid());
        assertNull(book.bestAsk());
    }

    @Test
    void buyBiggerThanRestingSellTradesWhatIsAvailableAndRestsTheRemainder() {
        submit("Ravi", Side.SELL, 3000, 10);

        List<Trade> trades = submit("Anu", Side.BUY, 3000, 25);

        assertEquals(1, trades.size());
        assertEquals(10, trades.get(0).quantity());
        assertNull(book.bestAsk());
        assertEquals(3000L, book.bestBid());
        assertEquals(15, quantityAt(Side.BUY, 3000));
    }

    @Test
    void buySmallerThanRestingSellLeavesRestingOrderAtTheFrontWithLessQuantity() {
        submit("Ravi", Side.SELL, 3000, 50);
        submit("Priya", Side.SELL, 3000, 5);

        List<Trade> trades = submit("Anu", Side.BUY, 3000, 20);

        assertEquals(1, trades.size());
        Order front = book.levelAt(Side.SELL, 3000).peekFirst();
        assertEquals("Ravi", front.getTrader());
        assertEquals(30, front.getRemainingQuantity());
        assertEquals(35, quantityAt(Side.SELL, 3000));
        assertNull(book.bestBid());
    }

    @Test
    void buyTradesAtTheRestingSellPriceNotItsOwnHigherPrice() {
        submit("Ravi", Side.SELL, 3000, 10);

        List<Trade> trades = submit("Anu", Side.BUY, 3200, 10);

        assertEquals(3000, trades.get(0).price());
    }

    @Test
    void whenTwoSellersAskTheSamePriceTheEarlierOneTradesFirst() {
        submit("Ravi", Side.SELL, 3000, 10);
        submit("Priya", Side.SELL, 3000, 10);

        List<Trade> trades = submit("Anu", Side.BUY, 3000, 10);

        assertEquals(1, trades.size());
        assertEquals("Ravi", trades.get(0).seller());
        assertEquals("Priya", book.levelAt(Side.SELL, 3000).peekFirst().getTrader());
    }

    @Test
    void cheaperSellThatArrivedLaterTradesBeforeAnEarlierMoreExpensiveSell() {
        submit("Ravi", Side.SELL, 3100, 10);   // arrived first, but dearer
        submit("Priya", Side.SELL, 3000, 10);  // arrived later, but cheaper

        List<Trade> trades = submit("Anu", Side.BUY, 3100, 10);

        assertEquals(1, trades.size());
        assertEquals("Priya", trades.get(0).seller());
        assertEquals(3000, trades.get(0).price());
        assertEquals(3100L, book.bestAsk());
    }

    @Test
    void bigBuySweepsSeveralAskLevelsInPriceOrderAndRemovesEmptyLevels() {
        submit("Ravi", Side.SELL, 3000, 10);
        submit("Priya", Side.SELL, 3100, 5);
        submit("Kumar", Side.SELL, 3200, 8);
        submit("Meena", Side.BUY, 2900, 4);

        List<Trade> trades = submit("Anu", Side.BUY, 3300, 25);

        assertEquals(3, trades.size());
        assertEquals("Ravi", trades.get(0).seller());
        assertEquals(3000, trades.get(0).price());
        assertEquals(10, trades.get(0).quantity());
        assertEquals("Priya", trades.get(1).seller());
        assertEquals(3100, trades.get(1).price());
        assertEquals(5, trades.get(1).quantity());
        assertEquals("Kumar", trades.get(2).seller());
        assertEquals(3200, trades.get(2).price());
        assertEquals(8, trades.get(2).quantity());

        assertNull(book.bestAsk());
        assertTrue(book.depth(Side.SELL, 10).isEmpty());
        assertEquals(List.of(new OrderBook.Level(3300, 2), new OrderBook.Level(2900, 4)),
                book.depth(Side.BUY, 10));
    }

    @Test
    void buyStopsSweepingAtItsLimitPrice() {
        submit("Ravi", Side.SELL, 3000, 10);
        submit("Priya", Side.SELL, 3200, 10);

        List<Trade> trades = submit("Anu", Side.BUY, 3100, 30);

        assertEquals(1, trades.size());
        assertEquals(3200L, book.bestAsk());
        assertEquals(20, quantityAt(Side.BUY, 3100));
    }

    // ---------------------------------------------------------------- incoming SELL (mirror)

    @Test
    void sellAboveBestBidDoesNotTradeAndBothOrdersRest() {
        submit("Anu", Side.BUY, 3000, 10);

        List<Trade> trades = submit("Ravi", Side.SELL, 3100, 10);

        assertTrue(trades.isEmpty());
        assertEquals(3000L, book.bestBid());
        assertEquals(3100L, book.bestAsk());
    }

    @Test
    void sellThatExactlyMatchesBidTradesFullyAndLeavesBookEmpty() {
        submit("Anu", Side.BUY, 3000, 10);

        List<Trade> trades = submit("Ravi", Side.SELL, 3000, 10);

        assertEquals(1, trades.size());
        Trade trade = trades.get(0);
        assertEquals("Anu", trade.buyer());
        assertEquals("Ravi", trade.seller());
        assertEquals(3000, trade.price());
        assertEquals(10, trade.quantity());
        assertNull(book.bestBid());
        assertNull(book.bestAsk());
    }

    @Test
    void sellBiggerThanRestingBuyTradesWhatIsAvailableAndRestsTheRemainder() {
        submit("Anu", Side.BUY, 3000, 10);

        List<Trade> trades = submit("Ravi", Side.SELL, 3000, 25);

        assertEquals(1, trades.size());
        assertEquals(10, trades.get(0).quantity());
        assertNull(book.bestBid());
        assertEquals(3000L, book.bestAsk());
        assertEquals(15, quantityAt(Side.SELL, 3000));
    }

    @Test
    void sellSmallerThanRestingBuyLeavesRestingOrderAtTheFrontWithLessQuantity() {
        submit("Anu", Side.BUY, 3000, 50);
        submit("Kumar", Side.BUY, 3000, 5);

        List<Trade> trades = submit("Ravi", Side.SELL, 3000, 20);

        assertEquals(1, trades.size());
        Order front = book.levelAt(Side.BUY, 3000).peekFirst();
        assertEquals("Anu", front.getTrader());
        assertEquals(30, front.getRemainingQuantity());
        assertEquals(35, quantityAt(Side.BUY, 3000));
        assertNull(book.bestAsk());
    }

    @Test
    void sellTradesAtTheRestingBuyPriceNotItsOwnLowerPrice() {
        submit("Anu", Side.BUY, 3200, 10);

        List<Trade> trades = submit("Ravi", Side.SELL, 3000, 10);

        assertEquals(3200, trades.get(0).price());
    }

    @Test
    void whenTwoBuyersBidTheSamePriceTheEarlierOneTradesFirst() {
        submit("Anu", Side.BUY, 3000, 10);
        submit("Kumar", Side.BUY, 3000, 10);

        List<Trade> trades = submit("Ravi", Side.SELL, 3000, 10);

        assertEquals(1, trades.size());
        assertEquals("Anu", trades.get(0).buyer());
        assertEquals("Kumar", book.levelAt(Side.BUY, 3000).peekFirst().getTrader());
    }

    @Test
    void higherBuyThatArrivedLaterTradesBeforeAnEarlierLowerBuy() {
        submit("Anu", Side.BUY, 3000, 10);    // arrived first, but lower
        submit("Kumar", Side.BUY, 3100, 10);  // arrived later, but higher

        List<Trade> trades = submit("Ravi", Side.SELL, 3000, 10);

        assertEquals(1, trades.size());
        assertEquals("Kumar", trades.get(0).buyer());
        assertEquals(3100, trades.get(0).price());
        assertEquals(3000L, book.bestBid());
    }

    @Test
    void bigSellSweepsSeveralBidLevelsInPriceOrderAndRemovesEmptyLevels() {
        submit("Ravi", Side.BUY, 3200, 10);
        submit("Priya", Side.BUY, 3100, 5);
        submit("Kumar", Side.BUY, 3000, 8);
        submit("Meena", Side.SELL, 3400, 4);

        List<Trade> trades = submit("Anu", Side.SELL, 2900, 25);

        assertEquals(3, trades.size());
        assertEquals("Ravi", trades.get(0).buyer());
        assertEquals(3200, trades.get(0).price());
        assertEquals(10, trades.get(0).quantity());
        assertEquals("Priya", trades.get(1).buyer());
        assertEquals(3100, trades.get(1).price());
        assertEquals(5, trades.get(1).quantity());
        assertEquals("Kumar", trades.get(2).buyer());
        assertEquals(3000, trades.get(2).price());
        assertEquals(8, trades.get(2).quantity());

        assertNull(book.bestBid());
        assertTrue(book.depth(Side.BUY, 10).isEmpty());
        assertEquals(List.of(new OrderBook.Level(2900, 2), new OrderBook.Level(3400, 4)),
                book.depth(Side.SELL, 10));
    }

    @Test
    void sellStopsSweepingAtItsLimitPrice() {
        submit("Anu", Side.BUY, 3200, 10);
        submit("Kumar", Side.BUY, 3000, 10);

        List<Trade> trades = submit("Ravi", Side.SELL, 3100, 30);

        assertEquals(1, trades.size());
        assertEquals(3000L, book.bestBid());
        assertEquals(20, quantityAt(Side.SELL, 3100));
    }

    // ---------------------------------------------------------------- trade details

    @Test
    void tradesGetIncreasingIdsAndRecordBothOrderIdsAndTheIncomingSequence() {
        submit("Ravi", Side.SELL, 3000, 5);      // order id 1
        submit("Priya", Side.SELL, 3100, 5);     // order id 2

        List<Trade> trades = submit("Anu", Side.BUY, 3100, 10); // order id 3, sequence 3

        assertEquals(1, trades.get(0).tradeId());
        assertEquals(2, trades.get(1).tradeId());
        assertEquals(3, trades.get(0).buyOrderId());
        assertEquals(1, trades.get(0).sellOrderId());
        assertEquals(3, trades.get(0).sequence());
        assertEquals(2, trades.get(1).sellOrderId());
    }

    @Test
    void submittingANullOrderIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> engine.submitLimit(null));
    }
}
