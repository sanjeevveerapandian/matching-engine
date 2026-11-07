package com.sanjeev.sim;

import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;
import com.sanjeev.infra.Command;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A repeatable list of random commands: the same seed always gives exactly the same list.
 *
 * <p>Mix: about 60% new limit orders (85% GTC, 10% IOC, 5% FOK), 10% market orders, 15% cancels
 * and 15% modifies. Cancels and modifies pick any earlier order id, so some target orders that
 * are already gone; the engine answers those with a rejection, which is part of the mix.
 * Prices are spread over 2900..3100 paise so the book grows reasonably deep.
 *
 * <p>Unlike {@link OrderGenerator}, this does not look at a book, so the whole list can be
 * created up front and fed through the sequencer.
 */
public final class CommandGenerator {

    private static final String[] TRADERS = {"Ravi", "Anu", "Priya", "Kumar", "Meena"};
    private static final int MIN_PRICE = 2900;
    private static final int PRICE_SPAN = 201; // 2900 .. 3100

    private CommandGenerator() { }

    public static List<Command> generate(long seed, int count) {
        Random random = new Random(seed);
        List<Command> commands = new ArrayList<>(count);
        long nextOrderId = 1;
        for (int i = 0; i < count; i++) {
            int roll = random.nextInt(100);
            if (nextOrderId > 1 && roll >= 70) {
                long target = 1 + random.nextInt((int) (nextOrderId - 1));
                if (roll < 85) {
                    commands.add(new Command.CancelOrder(target));
                } else {
                    commands.add(new Command.ModifyOrder(target, randomPrice(random), randomQuantity(random)));
                }
            } else if (roll >= 60 && roll < 70) {
                commands.add(Command.NewOrder.market(nextOrderId++, randomTrader(random),
                        randomSide(random), randomQuantity(random)));
            } else {
                commands.add(Command.NewOrder.limit(nextOrderId++, randomTrader(random),
                        randomSide(random), randomPrice(random), randomQuantity(random),
                        randomTimeInForce(random)));
            }
        }
        return commands;
    }

    private static String randomTrader(Random random) {
        return TRADERS[random.nextInt(TRADERS.length)];
    }

    private static Side randomSide(Random random) {
        return random.nextBoolean() ? Side.BUY : Side.SELL;
    }

    private static long randomPrice(Random random) {
        return MIN_PRICE + random.nextInt(PRICE_SPAN);
    }

    private static long randomQuantity(Random random) {
        return 1 + random.nextInt(100);
    }

    private static TimeInForce randomTimeInForce(Random random) {
        int roll = random.nextInt(100);
        if (roll < 85) {
            return TimeInForce.GTC;
        }
        return roll < 95 ? TimeInForce.IOC : TimeInForce.FOK;
    }
}
