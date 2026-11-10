package com.sanjeev.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.engine.MatchingEngine;
import com.sanjeev.engine.Order;
import com.sanjeev.engine.OrderBook;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.Trade;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
        assertTrue(result.orderIdsChecked() > 0);
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
        MatchingEngine engine = new MatchingEngine();
        engine.submitLimit(new Order(1, "Ravi", Side.SELL, 3000, 10, engine.nextSequence()));
        return engine.book();
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

    // ---------------------------------------------------------------- the price-time priority check itself

    private static Trade tradeAgainstSeller(long tradeId, long sellerId, long price) {
        return new Trade(tradeId, 99, sellerId, "Anu", "Seller" + sellerId, price, 1, 99);
    }

    private static Map<Long, Simulation.RestingInfo> restingSellers() {
        Map<Long, Simulation.RestingInfo> resting = new HashMap<>();
        resting.put(1L, new Simulation.RestingInfo(3000, 1));  // oldest at 3000
        resting.put(2L, new Simulation.RestingInfo(3000, 2));  // second at 3000
        resting.put(3L, new Simulation.RestingInfo(3100, 3));  // dearer
        return resting;
    }

    @Test
    void priorityCheckPassesForTradesAtRestingPricesBestPriceFirstAndOldestFirst() {
        List<Trade> sweep = List.of(
                tradeAgainstSeller(1, 1, 3000), tradeAgainstSeller(2, 2, 3000), tradeAgainstSeller(3, 3, 3100));

        Simulation.verifyPriority(sweep, Side.BUY, restingSellers());
    }

    @Test
    void priorityCheckAcceptsAnIncomingOrderThatMadeNoTrades() {
        Simulation.verifyPriority(List.of(), Side.BUY, restingSellers());
    }

    @Test
    void priorityCheckDetectsATradeThatHappenedAtTheIncomingOrdersPriceInsteadOfTheRestingPrice() {
        List<Trade> wrong = List.of(tradeAgainstSeller(1, 1, 3200)); // the seller rested at 3000

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Simulation.verifyPriority(wrong, Side.BUY, restingSellers()));
        assertTrue(e.getMessage().contains("RESTING order's price"), e.getMessage());
    }

    @Test
    void priorityCheckDetectsADearSellerServedBeforeACheapOne() {
        List<Trade> wrong = List.of(tradeAgainstSeller(1, 3, 3100), tradeAgainstSeller(2, 1, 3000));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Simulation.verifyPriority(wrong, Side.BUY, restingSellers()));
        assertTrue(e.getMessage().contains("best price first"), e.getMessage());
    }

    @Test
    void priorityCheckDetectsANewerOrderServedBeforeAnOlderOneAtTheSamePrice() {
        List<Trade> wrong = List.of(tradeAgainstSeller(1, 2, 3000), tradeAgainstSeller(2, 1, 3000));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Simulation.verifyPriority(wrong, Side.BUY, restingSellers()));
        assertTrue(e.getMessage().contains("oldest order first"), e.getMessage());
    }

    @Test
    void priorityCheckForAnIncomingSellerExpectsTheDearestBuyersFirst() {
        Map<Long, Simulation.RestingInfo> buyers = new HashMap<>();
        buyers.put(1L, new Simulation.RestingInfo(3100, 1));
        buyers.put(2L, new Simulation.RestingInfo(3000, 2));
        Trade dearBuyer = new Trade(1, 1, 99, "Buyer1", "Ravi", 3100, 1, 99);
        Trade cheapBuyer = new Trade(2, 2, 99, "Buyer2", "Ravi", 3000, 1, 99);

        Simulation.verifyPriority(List.of(dearBuyer, cheapBuyer), Side.SELL, buyers);
        assertThrows(IllegalStateException.class,
                () -> Simulation.verifyPriority(List.of(cheapBuyer, dearBuyer), Side.SELL, buyers));
    }

    @Test
    void priorityCheckDetectsATradeAgainstAnOrderThatWasNeverSeenResting() {
        List<Trade> ghost = List.of(tradeAgainstSeller(1, 42, 3000));

        assertThrows(IllegalStateException.class,
                () -> Simulation.verifyPriority(ghost, Side.BUY, restingSellers()));
    }

    // ---------------------------------------------------------------- the command-line run

    @Test
    void theCommandLineRunPrintsASummaryAndTheConservationCheck() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Simulation.main(new String[] {"3", "300"});
        } finally {
            System.setOut(original);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Seed:") && output.contains("3"), output);
        assertTrue(output.contains("Operations:") && output.contains("300"), output);
        assertTrue(output.contains("Conservation:") && output.contains("OK"), output);
    }

    @Test
    void ordersSubmittedIsLimitPlusMarketOrders() {
        Simulation.Result result = Simulation.run(5, 500);

        assertEquals(result.limitOrders() + result.marketOrders(), result.ordersSubmitted());
        assertTrue(result.ordersSubmitted() > 0);
    }
}
