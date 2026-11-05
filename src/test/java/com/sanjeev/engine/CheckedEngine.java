package com.sanjeev.engine;

import java.util.List;
import java.util.function.Supplier;

/**
 * Test helper: a {@link MatchingEngine} that runs {@link BookInvariants#check} after EVERY
 * operation, including operations that were rejected with an exception. A test using this
 * fails at the exact call that corrupted the book.
 */
final class CheckedEngine {

    private final MatchingEngine engine = new MatchingEngine();

    List<Trade> submitLimit(Order order) {
        return checked(() -> engine.submitLimit(order));
    }

    List<Trade> submitMarket(Order order) {
        return checked(() -> engine.submitMarket(order));
    }

    boolean cancel(long orderId) {
        return checked(() -> engine.cancel(orderId));
    }

    List<Trade> modify(long orderId, long newPrice, long newQuantity) {
        return checked(() -> engine.modify(orderId, newPrice, newQuantity));
    }

    long nextSequence() {
        return engine.nextSequence();
    }

    OrderBook book() {
        return engine.book();
    }

    private <T> T checked(Supplier<T> operation) {
        T result;
        try {
            result = operation.get();
        } catch (RuntimeException e) {
            BookInvariants.check(engine.book()); // a rejected operation must not corrupt the book
            throw e;
        }
        BookInvariants.check(engine.book());
        return result;
    }
}
