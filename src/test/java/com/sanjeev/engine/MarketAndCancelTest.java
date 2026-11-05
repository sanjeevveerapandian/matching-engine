package com.sanjeev.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MarketAndCancelTest {

    private MatchingEngine engine;
    private OrderBook book;
    private long nextId;

    @BeforeEach
    void freshEngine() {
        engine = new MatchingEngine();
        book = engine.book();
        nextId = 1;
    }

    /** Whatever a test did, the index must hold exactly the resting orders. */
    @AfterEach
    void indexAlwaysMatchesTheRestingOrders() {
        assertEquals(book.restingOrderCount(), book.indexSize());
    }

    /** Submits a limit order (id and sequence from one counter) and returns it. */
    private Order limit(String trader, Side side, long price, long qty) {
        Order order = new Order(nextId, trader, side, price, qty, nextId);
        nextId++;
        engine.submitLimit(order);
        return order;
    }

    /** Creates a market order without submitting it. */
    private Order market(String trader, Side side, long qty) {
        Order order = Order.market(nextId, trader, side, qty, nextId);
        nextId++;
        return order;
    }

    // ---------------------------------------------------------------- market orders

    @Test
    void marketBuyFillsAcrossSeveralAskLevelsCheapestFirst() {
        limit("Ravi", Side.SELL, 3000, 10);
        limit("Priya", Side.SELL, 3100, 5);
        limit("Kumar", Side.SELL, 3200, 8);
        Order anu = market("Anu", Side.BUY, 20);

        List<Trade> trades = engine.submitMarket(anu);

        assertEquals(3, trades.size());
        assertEquals("Ravi", trades.get(0).seller());
        assertEquals(3000, trades.get(0).price());
        assertEquals(10, trades.get(0).quantity());
        assertEquals("Priya", trades.get(1).seller());
        assertEquals(3100, trades.get(1).price());
        assertEquals(5, trades.get(1).quantity());
        assertEquals("Kumar", trades.get(2).seller());
        assertEquals(3200, trades.get(2).price());
        assertEquals(5, trades.get(2).quantity());
        assertTrue(anu.isFullyFilled());
        assertEquals(3200L, book.bestAsk());
        assertEquals(3, book.levelAt(Side.SELL, 3200).peekFirst().getRemainingQuantity());
        assertNull(book.bestBid());
    }

    @Test
    void marketSellFillsAcrossSeveralBidLevelsHighestFirst() {
        limit("Ravi", Side.BUY, 3200, 10);
        limit("Priya", Side.BUY, 3100, 5);
        limit("Kumar", Side.BUY, 3000, 8);
        Order anu = market("Anu", Side.SELL, 20);

        List<Trade> trades = engine.submitMarket(anu);

        assertEquals(3, trades.size());
        assertEquals("Ravi", trades.get(0).buyer());
        assertEquals(3200, trades.get(0).price());
        assertEquals("Priya", trades.get(1).buyer());
        assertEquals(3100, trades.get(1).price());
        assertEquals("Kumar", trades.get(2).buyer());
        assertEquals(3000, trades.get(2).price());
        assertEquals(5, trades.get(2).quantity());
        assertTrue(anu.isFullyFilled());
        assertEquals(3000L, book.bestBid());
        assertNull(book.bestAsk());
    }

    @Test
    void marketBuyTradesAtTheRestingPriceNoMatterHowHighItIs() {
        limit("Ravi", Side.SELL, 99_900, 10);

        List<Trade> trades = engine.submitMarket(market("Anu", Side.BUY, 10));

        assertEquals(99_900, trades.get(0).price());
    }

    @Test
    void marketBuyOnAnEmptyBookTradesNothingAndRestsNothing() {
        Order anu = market("Anu", Side.BUY, 10);

        List<Trade> trades = engine.submitMarket(anu);

        assertTrue(trades.isEmpty());
        assertNull(book.bestBid());
        assertNull(book.bestAsk());
        assertEquals(0, book.indexSize());
        assertFalse(book.contains(anu.getId()));
    }

    @Test
    void marketSellOnAnEmptyBookTradesNothingAndRestsNothing() {
        Order anu = market("Anu", Side.SELL, 10);

        List<Trade> trades = engine.submitMarket(anu);

        assertTrue(trades.isEmpty());
        assertNull(book.bestBid());
        assertNull(book.bestAsk());
        assertEquals(0, book.indexSize());
    }

    @Test
    void marketBuyBiggerThanTheBookFillsWhatExistsAndCancelsTheRestInsteadOfResting() {
        limit("Ravi", Side.SELL, 3000, 10);
        limit("Priya", Side.SELL, 3100, 5);
        Order anu = market("Anu", Side.BUY, 40);

        List<Trade> trades = engine.submitMarket(anu);

        assertEquals(2, trades.size());
        assertEquals(15, trades.get(0).quantity() + trades.get(1).quantity());
        assertEquals(25, anu.getRemainingQuantity()); // cancelled, not rested
        assertNull(book.bestAsk());
        assertNull(book.bestBid());
        assertFalse(book.contains(anu.getId()));
        assertEquals(0, book.indexSize());
    }

    @Test
    void marketOrderIsNotAllowedThroughSubmitLimitAndLimitNotThroughSubmitMarket() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.submitLimit(market("Anu", Side.BUY, 10)));
        assertThrows(IllegalArgumentException.class,
                () -> engine.submitMarket(new Order(99, "Anu", Side.BUY, 3000, 10, 99)));
    }

    @Test
    void submittingANullMarketOrderIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> engine.submitMarket(null));
    }

    // ---------------------------------------------------------------- cancel

    @Test
    void cancellingARestingOrderRemovesItFromTheBookAndTheIndex() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10);
        Order priya = limit("Priya", Side.SELL, 3000, 5);

        assertTrue(engine.cancel(ravi.getId()));

        assertFalse(book.contains(ravi.getId()));
        assertEquals(1, book.indexSize());
        assertEquals(5, book.depth(Side.SELL, 5).get(0).totalQuantity());
        assertEquals(priya, book.levelAt(Side.SELL, 3000).peekFirst());
    }

    @Test
    void cancellingTheOnlyOrderAtAPriceRemovesThatPriceLevel() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10);
        limit("Priya", Side.SELL, 3100, 5);

        assertTrue(engine.cancel(ravi.getId()));

        assertNull(book.levelAt(Side.SELL, 3000));
        assertEquals(3100L, book.bestAsk());
        assertEquals(1, book.depth(Side.SELL, 5).size());
    }

    @Test
    void cancellingABuyOrderWorksTheSameWay() {
        limit("Anu", Side.BUY, 2900, 10);
        Order kumar = limit("Kumar", Side.BUY, 3000, 10);

        assertTrue(engine.cancel(kumar.getId()));

        assertEquals(2900L, book.bestBid());
        assertNull(book.levelAt(Side.BUY, 3000));
    }

    @Test
    void cancellingAnUnknownIdReturnsFalseAndChangesNothing() {
        limit("Ravi", Side.SELL, 3000, 10);

        assertFalse(engine.cancel(999));

        assertEquals(3000L, book.bestAsk());
        assertEquals(1, book.indexSize());
    }

    @Test
    void cancellingAnOrderThatWasAlreadyFullyFilledReturnsFalse() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10);
        Order anu = limit("Anu", Side.BUY, 3000, 10);

        assertFalse(engine.cancel(ravi.getId())); // was resting, then filled
        assertFalse(engine.cancel(anu.getId()));  // never rested at all
    }

    @Test
    void cancellingAPartlyFilledRestingOrderRemovesTheRest() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 50);
        limit("Anu", Side.BUY, 3000, 20);

        assertTrue(engine.cancel(ravi.getId()));

        assertNull(book.bestAsk());
        assertEquals(0, book.indexSize());
    }

    @Test
    void cancellingTheSameOrderTwiceSucceedsOnlyTheFirstTime() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10);

        assertTrue(engine.cancel(ravi.getId()));
        assertFalse(engine.cancel(ravi.getId()));
    }

    @Test
    void aCancelledOrderNoLongerTakesPartInMatching() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10);
        limit("Priya", Side.SELL, 3000, 10);
        engine.cancel(ravi.getId());

        List<Trade> trades = engine.submitLimit(new Order(nextId, "Anu", Side.BUY, 3000, 10, nextId));

        assertEquals(1, trades.size());
        assertEquals("Priya", trades.get(0).seller());
        assertEquals(10, ravi.getRemainingQuantity()); // Ravi never traded
    }

    // ---------------------------------------------------------------- the index

    @Test
    void indexSizeAlwaysEqualsTheNumberOfRestingOrders() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10);
        assertEquals(1, book.indexSize());
        assertEquals(book.restingOrderCount(), book.indexSize());

        limit("Priya", Side.SELL, 3100, 10);
        limit("Anu", Side.BUY, 2900, 10);
        assertEquals(3, book.indexSize());
        assertEquals(book.restingOrderCount(), book.indexSize());

        limit("Kumar", Side.BUY, 3000, 4);          // fills part of Ravi: still resting
        assertEquals(3, book.indexSize());
        assertEquals(book.restingOrderCount(), book.indexSize());

        limit("Meena", Side.BUY, 3100, 30);         // sweeps Ravi and Priya, rests the leftover
        assertEquals(2, book.indexSize());          // Anu and Meena
        assertEquals(book.restingOrderCount(), book.indexSize());

        engine.submitMarket(market("Suresh", Side.SELL, 5)); // eats into the best bid
        assertEquals(book.restingOrderCount(), book.indexSize());

        engine.cancel(ravi.getId());                 // already filled: no change
        assertEquals(book.restingOrderCount(), book.indexSize());
    }

    // ---------------------------------------------------------------- duplicate ids

    @Test
    void aSecondLimitOrderWithTheSameIdAsARestingOneIsRejectedBeforeAnyTrading() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10); // id 1

        assertThrows(IllegalArgumentException.class,
                () -> engine.submitLimit(new Order(ravi.getId(), "Anu", Side.BUY, 3000, 10, 50)));

        assertEquals(10, ravi.getRemainingQuantity()); // nothing traded
        assertEquals(1, book.indexSize());
    }

    @Test
    void aMarketOrderWithTheSameIdAsARestingOneIsRejected() {
        Order ravi = limit("Ravi", Side.SELL, 3000, 10);

        assertThrows(IllegalArgumentException.class, () ->
                engine.submitMarket(Order.market(ravi.getId(), "Anu", Side.BUY, 10, 50)));

        assertEquals(10, ravi.getRemainingQuantity());
    }
}
