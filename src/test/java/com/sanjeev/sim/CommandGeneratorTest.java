package com.sanjeev.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.infra.Command;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandGeneratorTest {

    @Test
    void theSameSeedGivesExactlyTheSameCommands() {
        assertEquals(CommandGenerator.generate(5, 5_000), CommandGenerator.generate(5, 5_000));
    }

    @Test
    void differentSeedsGiveDifferentCommands() {
        assertNotEquals(CommandGenerator.generate(5, 500), CommandGenerator.generate(6, 500));
    }

    @Test
    void theRequestedNumberOfCommandsIsReturned() {
        assertEquals(0, CommandGenerator.generate(1, 0).size());
        assertEquals(1_234, CommandGenerator.generate(1, 1_234).size());
    }

    @Test
    void theFirstCommandIsAlwaysANewOrderBecauseThereIsNothingToCancelYet() {
        for (long seed = 1; seed <= 50; seed++) {
            assertTrue(CommandGenerator.generate(seed, 1).get(0) instanceof Command.NewOrder);
        }
    }

    @Test
    void newOrderIdsAreUniqueAndCountUpFromOne() {
        long expected = 1;
        for (Command command : CommandGenerator.generate(7, 10_000)) {
            if (command instanceof Command.NewOrder order) {
                assertEquals(expected++, order.orderId());
            }
        }
    }

    @Test
    void cancelsAndModifiesOnlyPointAtOrdersThatHaveAlreadyBeenCreated() {
        long highestIdSoFar = 0;
        for (Command command : CommandGenerator.generate(8, 10_000)) {
            if (command instanceof Command.NewOrder order) {
                highestIdSoFar = order.orderId();
            } else if (command instanceof Command.CancelOrder cancel) {
                assertTrue(cancel.orderId() >= 1 && cancel.orderId() <= highestIdSoFar);
            } else if (command instanceof Command.ModifyOrder modify) {
                assertTrue(modify.orderId() >= 1 && modify.orderId() <= highestIdSoFar);
            }
        }
    }

    @Test
    void theMixIsRoughlyEightyFiveToTwentyPercentOrdersCancelsAndModifies() {
        List<Command> commands = CommandGenerator.generate(9, 50_000);
        long newOrders = commands.stream().filter(c -> c instanceof Command.NewOrder).count();
        long cancels = commands.stream().filter(c -> c instanceof Command.CancelOrder).count();
        long modifies = commands.stream().filter(c -> c instanceof Command.ModifyOrder).count();

        assertEquals(70.0, 100.0 * newOrders / commands.size(), 2.0);
        assertEquals(15.0, 100.0 * cancels / commands.size(), 2.0);
        assertEquals(15.0, 100.0 * modifies / commands.size(), 2.0);
    }

    @Test
    void pricesStayInsideTheBandAndQuantitiesAreSmallPositiveNumbers() {
        for (Command command : CommandGenerator.generate(10, 20_000)) {
            if (command instanceof Command.NewOrder order) {
                assertTrue(order.quantity() >= 1 && order.quantity() <= 100);
                if (order.priceInPaise() != null) {
                    assertTrue(order.priceInPaise() >= 2900 && order.priceInPaise() <= 3100);
                }
            } else if (command instanceof Command.ModifyOrder modify) {
                assertTrue(modify.newPriceInPaise() >= 2900 && modify.newPriceInPaise() <= 3100);
                assertTrue(modify.newQuantity() >= 1 && modify.newQuantity() <= 100);
            }
        }
    }
}
