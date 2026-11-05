package com.sanjeev.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.engine.Order;
import com.sanjeev.engine.OrderBook;
import com.sanjeev.engine.OrderType;
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
