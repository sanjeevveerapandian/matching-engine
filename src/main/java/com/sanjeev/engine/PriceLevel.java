package com.sanjeev.engine;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;

/**
 * All resting orders at ONE price, kept in arrival order (first in, first out).
 *
 * <p>This is where time priority lives: orders are only ever added at the back,
 * so the front of the queue is always the earliest arrival.
 */
public final class PriceLevel {

    private final long priceInPaise;
    private final Deque<Order> queue = new ArrayDeque<>();

    public PriceLevel(long priceInPaise) {
        if (priceInPaise <= 0) {
            throw new IllegalArgumentException("price must be positive, got " + priceInPaise);
        }
        this.priceInPaise = priceInPaise;
    }

    /** Add an order at the back of the queue. O(1). */
    public void add(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("order must not be null");
        }
        if (order.getPriceInPaise() != priceInPaise) {
            throw new IllegalArgumentException(
                    "order price " + order.getPriceInPaise() + " does not match level " + priceInPaise);
        }
        queue.addLast(order);
    }

    /** The oldest order in the queue (next to trade), or null if empty. O(1). */
    public Order peekFirst() {
        return queue.peekFirst();
    }

    /** Remove and return the oldest order, or null if empty. O(1). */
    public Order removeFirst() {
        return queue.pollFirst();
    }

    /** Sum of the unfilled quantity of every order here. O(n) in orders at this price. */
    public long totalQuantity() {
        long total = 0;
        for (Order order : queue) {
            total += order.getRemainingQuantity();
        }
        return total;
    }

    /** Read-only view of the orders, earliest first. */
    public Iterable<Order> orders() {
        return Collections.unmodifiableCollection(queue);
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public long getPriceInPaise() {
        return priceInPaise;
    }
}
