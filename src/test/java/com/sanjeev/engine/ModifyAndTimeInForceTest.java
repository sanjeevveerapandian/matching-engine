package com.sanjeev.engine;

import static com.sanjeev.engine.BookTestSupport.quantityAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Every operation here goes through CheckedEngine, so the book is verified after each call. */
class ModifyAndTimeInForceTest {

    private CheckedEngine engine;
    private OrderBook book;
    private long nextId;

    @BeforeEach
    void freshEngine() {
        engine = new CheckedEngine();
        book = engine.book();
        nextId = 1;
    }

    /** Submits a GTC limit order (id and sequence from one counter) and returns it. */
    private Order limit(String trader, Side side, long price, long qty) {
        Order order = new Order(nextId, trader, side, price, qty, nextId);
        nextId++;
        engine.submitLimit(order);
        return order;
    }

    /** Creates a limit order with a time in force, without submitting it. */
    private Order withTif(String trader, Side side, long price, long qty, TimeInForce tif) {
        Order order = Order.limit(nextId, trader, side, price, qty, nextId, tif);
        nextId++;
        return order;
    }

    // ---------------------------------------------------------------- modify

    @Test
    void reducingQuantityAtTheSamePriceKeepsTimePriority() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10);
        limit("Priya", Side.SELL, 3000, 10);

        List<Trade> fromModify = engine.modify(ravi.getId(), 3000, 4);
        List<Trade> trades = engine.submitLimit(
                new Order(nextId, "Anu", Side.BUY, 3000, 6, nextId));

        assertTrue(fromModify.isEmpty());
        assertEquals(2, trades.size());
        assertEquals("Ravi", trades.get(0).seller()); // still first in line
        assertEquals(4, trades.get(0).quantity());
        assertEquals("Priya", trades.get(1).seller());
        assertEquals(2, trades.get(1).quantity());
    }

    @Test
    void reducingABuyOrderKeepsItsPlaceInTheQueueToo() {
        Order anu = limit("Anu", Side.BUY, 3000, 10);
        limit("Kumar", Side.BUY, 3000, 10);

        engine.modify(anu.getId(), 3000, 3);

        assertEquals(anu, book.levelAt(Side.BUY, 3000).peekFirst());
        assertEquals(3, anu.getRemainingQuantity());
        assertEquals(13, quantityAt(book, Side.BUY, 3000));
    }

    @Test
    void reducingAPartlyFilledOrderKeepsTheAmountAlreadyTraded() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 50);
        limit("Anu", Side.BUY, 3000, 20); // Ravi has 30 left, 20 traded

        engine.modify(ravi.getId(), 3000, 10);

        assertEquals(10, ravi.getRemainingQuantity());
        assertEquals(20, ravi.getOriginalQuantity() - ravi.getRemainingQuantity());
    }

    @Test
    void modifyingToTheSamePriceAndQuantityChangesNothing() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10);
        limit("Priya", Side.SELL, 3000, 10);

        List<Trade> trades = engine.modify(ravi.getId(), 3000, 10);

        assertTrue(trades.isEmpty());
        assertEquals(ravi, book.levelAt(Side.SELL, 3000).peekFirst());
        assertEquals(10, ravi.getRemainingQuantity());
    }

    @Test
    void changingThePriceLosesTimePriority() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10);
        limit("Priya", Side.SELL, 3000, 10);

        engine.modify(ravi.getId(), 3100, 10); // moves away...
        engine.modify(ravi.getId(), 3000, 10); // ...and back: now behind Priya

        List<Trade> trades = engine.submitLimit(
                new Order(nextId, "Anu", Side.BUY, 3000, 10, nextId));

        assertEquals(1, trades.size());
        assertEquals("Priya", trades.get(0).seller());
        assertTrue(book.contains(ravi.getId()));
    }

    @Test
    void increasingTheQuantityLosesTimePriority() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10);
        Order priya = limit("Priya", Side.SELL, 3000, 10);

        engine.modify(ravi.getId(), 3000, 15);

        assertEquals(priya, book.levelAt(Side.SELL, 3000).peekFirst());
        assertEquals(25, quantityAt(book, Side.SELL, 3000));
        List<Trade> trades = engine.submitLimit(
                new Order(nextId, "Anu", Side.BUY, 3000, 10, nextId));
        assertEquals("Priya", trades.get(0).seller());
    }

    @Test
    void aModifiedOrderKeepsItsIdAndTraderButGetsALaterSequence() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10);
        Order priya = limit("Priya", Side.SELL, 3100, 10);

        engine.modify(ravi.getId(), 3200, 10);

        Order replacement = book.levelAt(Side.SELL, 3200).peekFirst();
        assertEquals(ravi.getId(), replacement.getId());
        assertEquals("Ravi", replacement.getTrader());
        assertTrue(replacement.getSequence() > priya.getSequence());
    }

    @Test
    void modifyingPriceSoItNowCrossesTradesImmediatelyAtTheRestingPrice() {
        limit("Ravi", Side.SELL, 3200, 10);
        Order anu = limit("Anu", Side.BUY, 3000, 10);

        List<Trade> trades = engine.modify(anu.getId(), 3200, 10);

        assertEquals(1, trades.size());
        assertEquals(3200, trades.get(0).price()); // Ravi was resting, so his price
        assertEquals(10, trades.get(0).quantity());
        assertEquals("Anu", trades.get(0).buyer());
        assertNull(book.bestBid());
        assertNull(book.bestAsk());
    }

    @Test
    void modifyingPriceToPartlyCrossTradesWhatIsAvailableAndRestsTheRest() {
        limit("Ravi", Side.SELL, 3100, 3);
        Order anu = limit("Anu", Side.BUY, 3000, 5);

        List<Trade> trades = engine.modify(anu.getId(), 3100, 5);

        assertEquals(1, trades.size());
        assertEquals(3, trades.get(0).quantity());
        assertNull(book.bestAsk());
        assertEquals(3100L, book.bestBid());
        assertEquals(2, quantityAt(book, Side.BUY, 3100));
    }

    @Test
    void modifyingAnOrderThatIsNotRestingIsRejected() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10);
        limit("Anu", Side.BUY, 3000, 10); // fills Ravi completely

        assertThrows(IllegalArgumentException.class, () -> engine.modify(ravi.getId(), 3000, 5));
        assertThrows(IllegalArgumentException.class, () -> engine.modify(999, 3000, 5));
    }

    @Test
    void modifyWithAnInvalidPriceOrQuantityIsRejectedAndLeavesTheOrderUntouched() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10);
        Order priya = limit("Priya", Side.SELL, 3000, 10);

        assertThrows(IllegalArgumentException.class, () -> engine.modify(ravi.getId(), 0, 10));
        assertThrows(IllegalArgumentException.class, () -> engine.modify(ravi.getId(), 3000, 0));
        assertThrows(IllegalArgumentException.class, () -> engine.modify(ravi.getId(), -5, -5));

        assertEquals(ravi, book.levelAt(Side.SELL, 3000).peekFirst());
        assertEquals(10, ravi.getRemainingQuantity());
        assertTrue(book.contains(priya.getId()));
    }

    // ---------------------------------------------------------------- IOC

    @Test
    void iocBuyTradesWhatItCanThenCancelsTheRestInsteadOfResting() {
        limit("Ravi", Side.SELL, 3000, 10);
        Order anu = withTif("Anu", Side.BUY, 3000, 25, TimeInForce.IOC);

        List<Trade> trades = engine.submitLimit(anu);

        assertEquals(1, trades.size());
        assertEquals(10, trades.get(0).quantity());
        assertEquals(15, anu.getRemainingQuantity());
        assertNull(book.bestBid());
        assertNull(book.bestAsk());
        assertFalse(book.contains(anu.getId()));
    }

    @Test
    void iocSellTradesWhatItCanThenCancelsTheRestInsteadOfResting() {
        limit("Anu", Side.BUY, 3000, 10);
        Order ravi = withTif("Ravi", Side.SELL, 3000, 25, TimeInForce.IOC);

        List<Trade> trades = engine.submitLimit(ravi);

        assertEquals(1, trades.size());
        assertEquals(15, ravi.getRemainingQuantity());
        assertNull(book.bestAsk());
        assertNull(book.bestBid());
    }

    @Test
    void iocThatCannotTradeAtAllDoesNothingAndDoesNotRest() {
        limit("Ravi", Side.SELL, 3100, 10);
        Order anu = withTif("Anu", Side.BUY, 3000, 10, TimeInForce.IOC);

        List<Trade> trades = engine.submitLimit(anu);

        assertTrue(trades.isEmpty());
        assertNull(book.bestBid());
        assertEquals(10, quantityAt(book, Side.SELL, 3100));
    }

    @Test
    void iocStopsAtItsLimitPrice() {
        limit("Ravi", Side.SELL, 3000, 10);
        limit("Priya", Side.SELL, 3200, 10);
        Order anu = withTif("Anu", Side.BUY, 3100, 30, TimeInForce.IOC);

        List<Trade> trades = engine.submitLimit(anu);

        assertEquals(1, trades.size());
        assertEquals(3000, trades.get(0).price());
        assertEquals(20, anu.getRemainingQuantity());
        assertEquals(10, quantityAt(book, Side.SELL, 3200));
        assertNull(book.bestBid());
    }

    @Test
    void gtcOrderCreatedWithTheFactoryStillRestsItsLeftover() {
        limit("Ravi", Side.SELL, 3000, 10);
        Order anu = withTif("Anu", Side.BUY, 3000, 25, TimeInForce.GTC);

        engine.submitLimit(anu);

        assertEquals(3000L, book.bestBid());
        assertEquals(15, quantityAt(book, Side.BUY, 3000));
    }

    // ---------------------------------------------------------------- FOK

    @Test
    void fokBuyThatCanBeFilledCompletelyTradesEverythingAcrossLevels() {
        limit("Ravi", Side.SELL, 3000, 10);
        limit("Priya", Side.SELL, 3100, 10);
        Order anu = withTif("Anu", Side.BUY, 3100, 15, TimeInForce.FOK);

        List<Trade> trades = engine.submitLimit(anu);

        assertEquals(2, trades.size());
        assertEquals(10, trades.get(0).quantity());
        assertEquals(5, trades.get(1).quantity());
        assertTrue(anu.isFullyFilled());
        assertEquals(5, quantityAt(book, Side.SELL, 3100));
        assertNull(book.bestBid());
    }

    @Test
    void fokSellThatCanBeFilledCompletelyTradesEverything() {
        limit("Anu", Side.BUY, 3100, 10);
        limit("Kumar", Side.BUY, 3000, 10);
        Order ravi = withTif("Ravi", Side.SELL, 3000, 20, TimeInForce.FOK);

        List<Trade> trades = engine.submitLimit(ravi);

        assertEquals(2, trades.size());
        assertTrue(ravi.isFullyFilled());
        assertNull(book.bestBid());
        assertNull(book.bestAsk());
    }

    @Test
    void fokForExactlyTheAvailableQuantityIsFilled() {
        limit("Ravi", Side.SELL, 3000, 10);
        Order anu = withTif("Anu", Side.BUY, 3000, 10, TimeInForce.FOK);

        List<Trade> trades = engine.submitLimit(anu);

        assertEquals(1, trades.size());
        assertTrue(anu.isFullyFilled());
    }

    @Test
    void fokWithNotEnoughQuantityMakesZeroTradesAndLeavesTheBookUnchanged() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10);
        Order priya = limit("Priya", Side.SELL, 3100, 5);
        limit("Kumar", Side.BUY, 2900, 7);
        String bookBefore = book.toString();
        Order anu = withTif("Anu", Side.BUY, 3100, 20, TimeInForce.FOK);

        List<Trade> trades = engine.submitLimit(anu);

        assertTrue(trades.isEmpty());
        assertEquals(bookBefore, book.toString());
        assertEquals(10, ravi.getRemainingQuantity());
        assertEquals(5, priya.getRemainingQuantity());
        assertEquals(20, anu.getRemainingQuantity());
        assertFalse(book.contains(anu.getId()));
    }

    @Test
    void fokIgnoresQuantityThatIsOnlyAvailableAtWorsePrices() {
        limit("Ravi", Side.SELL, 3000, 10);
        limit("Priya", Side.SELL, 3200, 100);
        Order anu = withTif("Anu", Side.BUY, 3100, 20, TimeInForce.FOK);

        List<Trade> trades = engine.submitLimit(anu);

        assertTrue(trades.isEmpty());
        assertEquals(10, quantityAt(book, Side.SELL, 3000));
        assertEquals(100, quantityAt(book, Side.SELL, 3200));
    }

    @Test
    void fokSellWithNotEnoughQuantityMakesZeroTrades() {
        limit("Anu", Side.BUY, 3000, 10);
        Order ravi = withTif("Ravi", Side.SELL, 3000, 11, TimeInForce.FOK);

        List<Trade> trades = engine.submitLimit(ravi);

        assertTrue(trades.isEmpty());
        assertEquals(10, quantityAt(book, Side.BUY, 3000));
        assertNull(book.bestAsk());
    }

    @Test
    void fokOnAnEmptyBookDoesNothing() {
        Order anu = withTif("Anu", Side.BUY, 3000, 10, TimeInForce.FOK);

        List<Trade> trades = engine.submitLimit(anu);

        assertTrue(trades.isEmpty());
        assertNull(book.bestBid());
        assertEquals(0, book.indexSize());
    }

    @Test
    void iocAndFokOrdersWithAnIdAlreadyRestingAreRejected() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10);

        assertThrows(IllegalArgumentException.class, () -> engine.submitLimit(
                Order.limit(ravi.getId(), "Anu", Side.BUY, 3000, 5, 50, TimeInForce.IOC)));
        assertThrows(IllegalArgumentException.class, () -> engine.submitLimit(
                Order.limit(ravi.getId(), "Anu", Side.BUY, 3000, 5, 51, TimeInForce.FOK)));

        assertEquals(10, ravi.getRemainingQuantity());
    }

    @Test
    void marketOrdersAreImmediateOrCancel() {
        assertEquals(TimeInForce.IOC, Order.market(1, "Anu", Side.BUY, 5, 1).getTimeInForce());
    }
}
