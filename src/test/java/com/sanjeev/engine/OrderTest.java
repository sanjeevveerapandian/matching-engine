package com.sanjeev.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OrderTest {

    private static Order sellTomatoes(long qty) {
        return new Order(1, Side.SELL, 4000, qty, 1);
    }

    @Test
    void newOrderKeepsAllTheDetailsItWasCreatedWith() {
        Order ravi = new Order(7, Side.SELL, 4000, 50, 3);

        assertEquals(7, ravi.getId());
        assertEquals(Side.SELL, ravi.getSide());
        assertEquals(4000, ravi.getPriceInPaise());
        assertEquals(50, ravi.getOriginalQuantity());
        assertEquals(3, ravi.getSequence());
    }

    @Test
    void newOrderStartsWithNothingFilled() {
        Order ravi = sellTomatoes(50);

        assertEquals(50, ravi.getRemainingQuantity());
        assertFalse(ravi.isFullyFilled());
    }

    @Test
    void partialFillReducesRemainingQuantityButNotOriginalQuantity() {
        Order ravi = sellTomatoes(50);

        ravi.fill(20);

        assertEquals(30, ravi.getRemainingQuantity());
        assertEquals(50, ravi.getOriginalQuantity());
        assertFalse(ravi.isFullyFilled());
    }

    @Test
    void fillingTheWholeRemainingQuantityMarksOrderFullyFilled() {
        Order ravi = sellTomatoes(50);

        ravi.fill(20);
        ravi.fill(30);

        assertEquals(0, ravi.getRemainingQuantity());
        assertTrue(ravi.isFullyFilled());
    }

    @Test
    void fillingMoreThanRemainingIsRejectedAndLeavesOrderUnchanged() {
        Order ravi = sellTomatoes(50);

        assertThrows(IllegalArgumentException.class, () -> ravi.fill(51));

        assertEquals(50, ravi.getRemainingQuantity());
    }

    @Test
    void fillingZeroOrNegativeQuantityIsRejected() {
        Order ravi = sellTomatoes(50);

        assertThrows(IllegalArgumentException.class, () -> ravi.fill(0));
        assertThrows(IllegalArgumentException.class, () -> ravi.fill(-5));
    }

    @Test
    void orderWithZeroOrNegativePriceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Order(1, Side.BUY, 0, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new Order(1, Side.BUY, -100, 10, 1));
    }

    @Test
    void orderWithZeroOrNegativeQuantityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Order(1, Side.BUY, 4000, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Order(1, Side.BUY, 4000, -10, 1));
    }

    @Test
    void orderWithoutASideIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Order(1, null, 4000, 10, 1));
    }

    @Test
    void orderRemembersWhichTraderPlacedIt() {
        Order ravi = new Order(1, "Ravi", Side.SELL, 4000, 50, 1);

        assertEquals("Ravi", ravi.getTrader());
    }

    @Test
    void orderCreatedWithoutATraderNameIsAnonymous() {
        assertEquals(Order.ANONYMOUS, sellTomatoes(50).getTrader());
    }

    @Test
    void orderWithNullOrBlankTraderNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Order(1, null, Side.SELL, 4000, 50, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Order(1, "  ", Side.SELL, 4000, 50, 1));
    }

    @Test
    void ordersCreatedWithAPriceAreLimitOrders() {
        assertEquals(OrderType.LIMIT, sellTomatoes(50).getType());
    }

    @Test
    void marketOrderHasMarketTypeAndKeepsItsOtherDetails() {
        Order anu = Order.market(5, "Anu", Side.BUY, 20, 9);

        assertEquals(OrderType.MARKET, anu.getType());
        assertEquals("Anu", anu.getTrader());
        assertEquals(Side.BUY, anu.getSide());
        assertEquals(20, anu.getRemainingQuantity());
        assertEquals(9, anu.getSequence());
    }

    @Test
    void marketOrderHasNoPriceSoAskingForOneFails() {
        Order anu = Order.market(5, "Anu", Side.BUY, 20, 9);

        assertThrows(IllegalStateException.class, anu::getPriceInPaise);
    }

    @Test
    void marketOrderCanBePartlyFilledLikeAnyOtherOrder() {
        Order anu = Order.market(5, "Anu", Side.BUY, 20, 9);

        anu.fill(8);

        assertEquals(12, anu.getRemainingQuantity());
    }

    @Test
    void marketOrderStillNeedsAPositiveQuantityASideAndATraderName() {
        assertThrows(IllegalArgumentException.class, () -> Order.market(1, "Anu", Side.BUY, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> Order.market(1, "Anu", null, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> Order.market(1, " ", Side.BUY, 10, 1));
    }

    @Test
    void ordersCreatedWithTheConstructorsAreGoodTillCancelled() {
        assertEquals(TimeInForce.GTC, sellTomatoes(50).getTimeInForce());
        assertEquals(TimeInForce.GTC, new Order(1, "Ravi", Side.SELL, 4000, 50, 1).getTimeInForce());
    }

    @Test
    void limitFactoryCreatesLimitOrdersWithTheRequestedTimeInForce() {
        Order ioc = Order.limit(1, "Anu", Side.BUY, 4000, 10, 1, TimeInForce.IOC);
        Order fok = Order.limit(2, "Anu", Side.BUY, 4000, 10, 2, TimeInForce.FOK);

        assertEquals(OrderType.LIMIT, ioc.getType());
        assertEquals(4000, ioc.getPriceInPaise());
        assertEquals(TimeInForce.IOC, ioc.getTimeInForce());
        assertEquals(TimeInForce.FOK, fok.getTimeInForce());
    }

    @Test
    void limitFactoryStillValidatesPriceQuantityAndTimeInForce() {
        assertThrows(IllegalArgumentException.class,
                () -> Order.limit(1, "Anu", Side.BUY, 0, 10, 1, TimeInForce.IOC));
        assertThrows(IllegalArgumentException.class,
                () -> Order.limit(1, "Anu", Side.BUY, 4000, 0, 1, TimeInForce.IOC));
        assertThrows(IllegalArgumentException.class,
                () -> Order.limit(1, "Anu", Side.BUY, 4000, 10, 1, null));
    }

    @Test
    void reducingAnOrderShrinksBothQuantitiesByTheSameAmount() {
        Order ravi = sellTomatoes(50);
        ravi.fill(20); // 30 left, 20 traded

        ravi.reduceQuantityTo(10);

        assertEquals(10, ravi.getRemainingQuantity());
        assertEquals(30, ravi.getOriginalQuantity());
    }

    @Test
    void reducingAnOrderToTheSameLargerOrNonPositiveQuantityIsRejected() {
        Order ravi = sellTomatoes(50);

        assertThrows(IllegalArgumentException.class, () -> ravi.reduceQuantityTo(50));
        assertThrows(IllegalArgumentException.class, () -> ravi.reduceQuantityTo(60));
        assertThrows(IllegalArgumentException.class, () -> ravi.reduceQuantityTo(0));
        assertEquals(50, ravi.getRemainingQuantity());
    }

    @Test
    void buyAndSellAreOppositesOfEachOther() {
        assertEquals(Side.SELL, Side.BUY.opposite());
        assertEquals(Side.BUY, Side.SELL.opposite());
    }
}
