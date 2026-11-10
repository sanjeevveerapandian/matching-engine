package com.sanjeev.api;

import com.sanjeev.engine.Trade;
import java.util.ArrayList;
import java.util.List;

/**
 * The last N trades, newest first: a fixed-size ring where each new trade overwrites the oldest.
 *
 * <p>NOT thread-safe, on purpose. It is only ever touched by the sequencer's engine thread: the
 * publisher adds trades to it there, and reads go through {@code Sequencer.query}, which also runs
 * there. One thread, so no locks.
 */
final class RecentTrades {

    private final Trade[] ring;
    private int next;  // where the next trade goes
    private int size;

    RecentTrades(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, got " + capacity);
        }
        this.ring = new Trade[capacity];
    }

    void add(Trade trade) {
        ring[next] = trade;
        next = (next + 1) % ring.length;
        if (size < ring.length) {
            size++;
        }
    }

    /** Up to {@code limit} trades, newest first. The list is a copy; trades are immutable. */
    List<Trade> latest(int limit) {
        int count = Math.min(Math.max(limit, 0), size);
        List<Trade> newestFirst = new ArrayList<>(count);
        int index = next;
        for (int i = 0; i < count; i++) {
            index = (index - 1 + ring.length) % ring.length;
            newestFirst.add(ring[index]);
        }
        return List.copyOf(newestFirst);
    }

    int size() {
        return size;
    }
}
