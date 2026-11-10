package com.sanjeev.api;

/**
 * The request was well-formed but the engine refused the new order (HTTP 422). The API checks
 * everything it can first, so this is a safety net rather than an everyday path.
 */
public class OrderRejectedException extends RuntimeException {

    public OrderRejectedException(String reason) {
        super("the order was rejected: " + reason);
    }
}
