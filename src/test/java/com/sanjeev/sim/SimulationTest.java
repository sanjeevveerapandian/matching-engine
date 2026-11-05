package com.sanjeev.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.engine.Order;
import com.sanjeev.engine.OrderBook;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.Trade;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimulationTest {

    private static double percent(int part, int whole) {
        return 100.0 * part / whole;
    }

    // ---------------------------------------------------------------- the big run

    @Test
    void hundredThousandSeededOperationsKeepEveryInvariantAndConserveQuantity() {
        // run() checks BookInvariants after EVERY operation and conservation at the end;
        // any violation throws and fails this test.
        Simulation.Result result = Simulation.run(42, 100_000);

        assertEquals(100_000, result.operations());
        assertEquals(100_000, result.limitOrders() + result.marketOrders()
                + result.cancels() + result.modifies());
        assertFalse(result.trades().isEmpty());
        assertTrue(result.tradedQuantity() > 0);
        assertTrue(result.ordersChecked() > 0);
    }

    @Test
    void manyDifferentSeedsAllKeepEveryInvariant() {
        for (long seed = 1; seed <= 20; seed++) {
            Simulation.run(seed, 20_000);
        }
    }

    // ---------------------------------------------------------------- determinism

    @Test
    void theSameSeedGivesExactlyTheSameTradesInTheSameOrder() {
        Simulation.Result first = Simulation.run(7, 30_000);
        Simulation.Result second = Simulation.run(7, 30_000);

        assertFalse(first.trades().isEmpty());
        assertEquals(first.trades(), second.trades()); // Trade is a record: compares every field
        assertEquals(first.tradedQuantity(), second.tradedQuantity());
        assertEquals(first.bestBid(), second.bestBid());
        assertEquals(first.bestAsk(), second.bestAsk());
        assertEquals(first.restingOrders(), second.restingOrders());
    }

    @Test
    void differentSeedsGiveDifferentTrades() {
        Simulation.Result a = Simulation.run(1, 10_000);
        Simulation.Result b = Simulation.run(2, 10_000);

        assertNotEquals(a.trades(), b.trades());
    }

    // ---------------------------------------------------------------- what the run looks like

    @Test
    void operationMixIsCloseToSixtyTenTwentyTenPercent() {
        Simulation.Result r = Simulation.run(42, 100_000);

        assertEquals(60, percent(r.limitOrders(), r.operations()), 3);
        assertEquals(10, percent(r.marketOrders(), r.operations()), 2);
        assertEquals(20, percent(r.cancels(), r.operations()), 3);
        assertEquals(10, percent(r.modifies(), r.operations()), 2);
    }

    @Test
    void everyTradeIsAtAPriceInsideTheAllowedBandWithPositiveQuantityAndConsecutiveIds() {
        List<Trade> trades = Simulation.run(42, 50_000).trades();

        long expectedId = 1;
        for (Trade trade : trades) {
            assertTrue(trade.price() >= OrderGenerator.MIN_PRICE
                    && trade.price() <= OrderGenerator.MAX_PRICE, "price out of band: " + trade);
            assertTrue(trade.quantity() > 0, "non-positive quantity: " + trade);
            assertEquals(expectedId++, trade.tradeId());
        }
    }

    // ---------------------------------------------------------------- the conservation check itself

    private static Simulation.Ledger ledger(long added, long filled, long cancelled) {
        Simulation.Ledger ledger = new Simulation.Ledger();
        ledger.added = added;
        ledger.filled = filled;
        ledger.cancelled = cancelled;
        return ledger;
    }

    private static OrderBook bookWithRestingRavi() {
        OrderBook book = new OrderBook();
        book.addResting(new Order(1, "Ravi", Side.SELL, 3000, 10, 1));
        return book;
    }

    @Test
    void conservationCheckPassesWhenEveryUnitIsAccountedFor() {
        Map<Long, Simulation.Ledger> ledgers = new HashMap<>();
        ledgers.put(1L, ledger(10, 0, 0)); // 10 added, all still resting

        Simulation.verifyConservation(ledgers, bookWithRestingRavi(), 0);
    }

    @Test
    void conservationCheckCountsFilledAndCancelledQuantityToo() {
        Map<Long, Simulation.Ledger> ledgers = new HashMap<>();
        ledgers.put(1L, ledger(16, 4, 2)); // 16 = 4 traded + 10 resting + 2 cancelled

        Simulation.verifyConservation(ledgers, bookWithRestingRavi(), 2);
    }

    @Test
    void conservationCheckDetectsQuantityThatWasCreatedFromNothing() {
        Map<Long, Simulation.Ledger> ledgers = new HashMap<>();
        ledgers.put(1L, ledger(9, 0, 0)); // book holds 10 but only 9 were ever added

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Simulation.verifyConservation(ledgers, bookWithRestingRavi(), 0));
        assertTrue(e.getMessage().contains("conservation broken for order 1"));
    }

    @Test
    void conservationCheckDetectsQuantityThatVanished() {
        Map<Long, Simulation.Ledger> ledgers = new HashMap<>();
        ledgers.put(1L, ledger(12, 0, 0)); // 12 added, only 10 resting, 2 lost

        assertThrows(IllegalStateException.class,
                () -> Simulation.verifyConservation(ledgers, bookWithRestingRavi(), 0));
    }

    @Test
    void conservationCheckDetectsARestingOrderNobodyAccountedFor() {
        Map<Long, Simulation.Ledger> ledgers = new HashMap<>(); // book has Ravi, ledgers do not

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Simulation.verifyConservation(ledgers, bookWithRestingRavi(), 0));
        assertTrue(e.getMessage().contains("accounted for"));
    }

    @Test
    void conservationCheckDetectsFillsThatDoNotMatchTheTradedQuantity() {
        Map<Long, Simulation.Ledger> ledgers = new HashMap<>();
        ledgers.put(1L, ledger(14, 4, 0)); // balances per order, but 4 filled needs 2 traded

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Simulation.verifyConservation(ledgers, bookWithRestingRavi(), 0));
        assertTrue(e.getMessage().contains("traded"));
    }
}
