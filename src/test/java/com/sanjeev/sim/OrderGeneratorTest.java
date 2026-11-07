package com.sanjeev.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.engine.Order;
import com.sanjeev.engine.OrderBook;
import com.sanjeev.engine.OrderType;
import com.sanjeev.engine.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OrderGeneratorTest {

    /** Operations from a generator on an empty book that never changes (nothing is applied). */
    private static List<Operation> generate(long seed, int count, AtomicLong sequence) {
        OrderGenerator generator = new OrderGenerator(seed, new OrderBook(), sequence::incrementAndGet);
        List<Operation> operations = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            operations.add(generator.next());
        }
        return operations;
    }

    @Test
    void theSameSeedProducesExactlyTheSameOperations() {
        List<String> first = generate(5, 2_000, new AtomicLong()).stream().map(Object::toString).toList();
        List<String> second = generate(5, 2_000, new AtomicLong()).stream().map(Object::toString).toList();

        assertEquals(first, second);
    }

    @Test
    void differentSeedsProduceDifferentOperations() {
        List<String> a = generate(5, 200, new AtomicLong()).stream().map(Object::toString).toList();
        List<String> b = generate(6, 200, new AtomicLong()).stream().map(Object::toString).toList();

        assertTrue(!a.equals(b));
    }

    @Test
    void onAnEmptyBookThereIsNothingToCancelOrModifySoOnlyOrdersAreGenerated() {
        for (Operation operation : generate(9, 5_000, new AtomicLong())) {
            assertTrue(operation instanceof Operation.SubmitLimit
                    || operation instanceof Operation.SubmitMarket, "unexpected: " + operation);
        }
    }

    @Test
    void limitPricesStayInsideThePlusMinusFivePercentBandAndQuantitiesAreOneToHundred() {
        for (Operation operation : generate(9, 20_000, new AtomicLong())) {
            Order order = switch (operation) {
                case Operation.SubmitLimit s -> s.order();
                case Operation.SubmitMarket s -> s.order();
                default -> throw new AssertionError("unexpected: " + operation);
            };
            assertTrue(order.getOriginalQuantity() >= 1 && order.getOriginalQuantity() <= 100);
            if (order.getType() == OrderType.LIMIT) {
                assertTrue(order.getPriceInPaise() >= OrderGenerator.MIN_PRICE
                        && order.getPriceInPaise() <= OrderGenerator.MAX_PRICE);
            }
        }
    }

    private static List<Long> limitPrices(int priceNoise, int count) {
        OrderGenerator generator = new OrderGenerator(4, new OrderBook(), new AtomicLong()::incrementAndGet, priceNoise);
        List<Long> prices = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (generator.next() instanceof Operation.SubmitLimit limit) {
                prices.add(limit.order().getPriceInPaise());
            }
        }
        return prices;
    }

    @Test
    void aSmallPriceNoiseKeepsPricesNextToTheMidOfAnEmptyBook() {
        for (long price : limitPrices(1, 5_000)) {
            assertTrue(price >= 2999 && price <= 3001, "price " + price);
        }
    }

    @Test
    void aBiggerPriceNoiseSpreadsPricesOverMoreLevelsButStaysInTheBand() {
        List<Long> narrow = limitPrices(5, 5_000);
        List<Long> wide = limitPrices(150, 5_000);

        assertTrue(wide.stream().distinct().count() > narrow.stream().distinct().count());
        for (long price : wide) {
            assertTrue(price >= OrderGenerator.MIN_PRICE && price <= OrderGenerator.MAX_PRICE);
        }
    }

    @Test
    void thePriceNoiseMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new OrderGenerator(1, new OrderBook(), () -> 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new OrderGenerator(1, new OrderBook(), () -> 1, -3));
    }

    @Test
    void theThreeArgumentConstructorKeepsTheOriginalDefaultNoise() {
        OrderGenerator defaulted = new OrderGenerator(8, new OrderBook(), new AtomicLong()::incrementAndGet);
        OrderGenerator explicit = new OrderGenerator(8, new OrderBook(), new AtomicLong()::incrementAndGet,
                OrderGenerator.PRICE_NOISE);

        for (int i = 0; i < 500; i++) {
            assertEquals(defaulted.next().toString(), explicit.next().toString());
        }
    }

    @Test
    void whenEveryOrderIsPassiveBuysAreBelowTheMidAndSellsAboveItOnAnEmptyBook() {
        OrderGenerator generator = new OrderGenerator(6, new OrderBook(), new AtomicLong()::incrementAndGet, 150, 100);
        int checked = 0;
        for (int i = 0; i < 5_000; i++) {
            if (generator.next() instanceof Operation.SubmitLimit limit) {
                Order order = limit.order();
                if (order.getSide() == Side.BUY) {
                    assertTrue(order.getPriceInPaise() < 3000, "buy at " + order.getPriceInPaise());
                } else {
                    assertTrue(order.getPriceInPaise() > 3000, "sell at " + order.getPriceInPaise());
                }
                checked++;
            }
        }
        assertTrue(checked > 1_000);
    }

    @Test
    void withNoPassiveOrdersTheFiveArgumentConstructorMatchesTheFourArgumentOne() {
        OrderGenerator four = new OrderGenerator(8, new OrderBook(), new AtomicLong()::incrementAndGet, 40);
        OrderGenerator five = new OrderGenerator(8, new OrderBook(), new AtomicLong()::incrementAndGet, 40, 0);

        for (int i = 0; i < 500; i++) {
            assertEquals(four.next().toString(), five.next().toString());
        }
    }

    @Test
    void thePassivePercentMustBeBetweenZeroAndOneHundred() {
        assertThrows(IllegalArgumentException.class,
                () -> new OrderGenerator(1, new OrderBook(), () -> 1, 40, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new OrderGenerator(1, new OrderBook(), () -> 1, 40, 101));
    }

    @Test
    void orderIdsAreUniqueAndSequencesComeFromTheSuppliedSource() {
        AtomicLong sequence = new AtomicLong(100);

        List<Order> orders = generate(3, 1_000, sequence).stream().map(op -> switch (op) {
            case Operation.SubmitLimit s -> s.order();
            case Operation.SubmitMarket s -> s.order();
            default -> throw new AssertionError("unexpected: " + op);
        }).toList();

        for (int i = 0; i < orders.size(); i++) {
            assertEquals(i + 1, orders.get(i).getId());
            assertEquals(101 + i, orders.get(i).getSequence());
        }
    }
}
