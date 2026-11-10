package com.sanjeev.api;

/**
 * There is no resting order with that id (HTTP 404). The engine only knows orders that are
 * resting, so this covers an id that never existed AND one that already filled or was cancelled.
 */
public class OrderNotFoundException extends RuntimeException {

    private final long orderId;

    public OrderNotFoundException(long orderId) {
        super("order " + orderId + " is not resting on the book (unknown, already filled, or already cancelled)");
        this.orderId = orderId;
    }

    public long orderId() {
        return orderId;
    }
}
