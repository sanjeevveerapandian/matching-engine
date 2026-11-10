package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.engine.Trade;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecentTradesTest {

    private static Trade trade(long id) {
        return new Trade(id, 1, 2, "Anu", "Ravi", 4000, 1, id);
    }

    private static List<Long> ids(List<Trade> trades) {
        return trades.stream().map(Trade::tradeId).toList();
    }

    @Test
    void anEmptyStoreHasNothingToShow() {
        assertTrue(new RecentTrades(5).latest(10).isEmpty());
    }

    @Test
    void tradesComeBackNewestFirst() {
        RecentTrades recent = new RecentTrades(5);
        for (long id = 1; id <= 3; id++) {
            recent.add(trade(id));
        }

        assertEquals(List.of(3L, 2L, 1L), ids(recent.latest(10)));
    }

    @Test
    void theLimitCapsHowManyAreReturned() {
        RecentTrades recent = new RecentTrades(10);
        for (long id = 1; id <= 8; id++) {
            recent.add(trade(id));
        }

        assertEquals(List.of(8L, 7L, 6L), ids(recent.latest(3)));
    }

    @Test
    void onceFullTheOldestTradeIsForgottenAsEachNewOneArrives() {
        RecentTrades recent = new RecentTrades(3);
        for (long id = 1; id <= 7; id++) {
            recent.add(trade(id));
        }

        assertEquals(List.of(7L, 6L, 5L), ids(recent.latest(10)));
        assertEquals(3, recent.size());
    }

    @Test
    void aStoreOfOneKeepsOnlyTheLatestTrade() {
        RecentTrades recent = new RecentTrades(1);
        recent.add(trade(1));
        recent.add(trade(2));

        assertEquals(List.of(2L), ids(recent.latest(5)));
    }

    @Test
    void aZeroOrNegativeLimitReturnsNothing() {
        RecentTrades recent = new RecentTrades(3);
        recent.add(trade(1));

        assertTrue(recent.latest(0).isEmpty());
        assertTrue(recent.latest(-4).isEmpty());
    }

    @Test
    void theReturnedListIsACopyThatLaterTradesDoNotChange() {
        RecentTrades recent = new RecentTrades(3);
        recent.add(trade(1));
        List<Trade> snapshot = recent.latest(3);

        recent.add(trade(2));

        assertEquals(List.of(1L), ids(snapshot));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(trade(9)));
    }

    @Test
    void aCapacityBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RecentTrades(0));
        assertThrows(IllegalArgumentException.class, () -> new RecentTrades(-1));
    }
}
