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
    void buyAndSellAreOppositesOfEachOther() {
        assertEquals(Side.SELL, Side.BUY.opposite());
        assertEquals(Side.BUY, Side.SELL.opposite());
    }
}
