package com.sanjeev.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceLevelTest {

    private static Order sell(long id, long qty) {
        return new Order(id, Side.SELL, 4000, qty, id);
    }

    @Test
    void newLevelIsEmpty() {
        PriceLevel level = new PriceLevel(4000);

        assertTrue(level.isEmpty());
        assertEquals(0, level.size());
        assertEquals(0, level.totalQuantity());
    }

    @Test
    void ordersStayInTheOrderTheyArrived() {
        PriceLevel level = new PriceLevel(4000);
        Order ravi = sell(1, 50);
        Order meena = sell(2, 30);
        level.add(ravi);
        level.add(meena);

        List<Order> inLine = new ArrayList<>();
        level.orders().forEach(inLine::add);

        assertEquals(List.of(ravi, meena), inLine);
    }

    @Test
    void totalQuantityAddsUpWhatIsStillUnfilled() {
        PriceLevel level = new PriceLevel(4000);
        Order ravi = sell(1, 50);
        level.add(ravi);
        level.add(sell(2, 30));

        ravi.fill(20);

        assertEquals(60, level.totalQuantity());
    }

    @Test
    void peekFirstShowsTheOldestOrderWithoutRemovingIt() {
        PriceLevel level = new PriceLevel(4000);
        Order ravi = sell(1, 50);
        level.add(ravi);
        level.add(sell(2, 30));

        assertEquals(ravi, level.peekFirst());
        assertEquals(2, level.size());
    }

    @Test
    void removeFirstTakesTheOldestOrderOutOfTheLine() {
        PriceLevel level = new PriceLevel(4000);
        Order ravi = sell(1, 50);
        Order meena = sell(2, 30);
        level.add(ravi);
        level.add(meena);

        assertEquals(ravi, level.removeFirst());
        assertEquals(meena, level.peekFirst());
        assertEquals(1, level.size());
    }

    @Test
    void peekAndRemoveOnAnEmptyLevelGiveNull() {
        PriceLevel level = new PriceLevel(4000);

        assertNull(level.peekFirst());
        assertNull(level.removeFirst());
    }

    @Test
    void orderAtADifferentPriceIsRejected() {
        PriceLevel level = new PriceLevel(4000);

        assertThrows(IllegalArgumentException.class,
                () -> level.add(new Order(1, Side.SELL, 4100, 10, 1)));
        assertTrue(level.isEmpty());
    }

    @Test
    void levelWithZeroOrNegativePriceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PriceLevel(0));
        assertThrows(IllegalArgumentException.class, () -> new PriceLevel(-5));
    }

    @Test
    void addingANullOrderIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PriceLevel(4000).add(null));
    }
}
