package com.sanjeev.api;

import com.sanjeev.engine.Trade;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pushes every trade to viewers, in the order the trades happened, without ever making the engine
 * thread wait.
 *
 * <p>The engine thread calls {@link #offer}, which puts the trade on a bounded queue and returns
 * at once. If the queue is full (viewers cannot keep up) the trade is DROPPED and counted: losing
 * a trade from a live tape is far better than slowing down the exchange. One worker thread takes
 * trades off the queue and sends them.
 *
 * <p>Nothing is lost for anyone who needs the truth: every trade is in the command log and in
 * {@code GET /api/trades}; this feed is only a convenience for screens.
 */
final class TradeFeed implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TradeFeed.class);

    private final BlockingQueue<Trade> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final Consumer<Trade> sink;
    private final Thread worker = new Thread(this::run, "trade-feed");

    TradeFeed(int capacity, Consumer<Trade> sink) {
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.sink = sink;
        worker.setDaemon(true);
    }

    void start() {
        worker.start();
    }

    /** Called on the engine thread. Never blocks. */
    void offer(Trade trade) {
        if (!queue.offer(trade)) {
            dropped.incrementAndGet();
        }
    }

    long dropped() {
        return dropped.get();
    }

    long sent() {
        return sent.get();
    }

    private void run() {
        try {
            while (true) {
                Trade trade = queue.take();
                try {
                    sink.accept(trade);
                    sent.incrementAndGet();
                } catch (RuntimeException e) {
                    LOG.warn("could not push trade {}: {}", trade.tradeId(), e.toString());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // close() asked us to stop
        }
    }

    @Override
    public void close() {
        worker.interrupt();
    }
}
