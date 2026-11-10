package com.sanjeev.api;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pushes the order book to viewers, at most once per interval, however fast the book changes.
 *
 * <p>The engine thread calls {@link #markChanged()}: that is ONE write to a flag, nothing else. A
 * separate thread wakes up every interval, and only if the flag is set does it take a fresh
 * snapshot of the book and send it. So a thousand changes inside 100 ms become one message, and the
 * message always shows the LATEST state, including after a burst ends (the last tick sends it).
 *
 * <p>The snapshot is requested through the exchange service, i.e. as a query on the engine thread:
 * this class never touches the engine itself.
 *
 * @param <T> what a book update looks like
 */
final class BookFeed<T> implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BookFeed.class);

    private final AtomicBoolean changed = new AtomicBoolean();
    private final AtomicLong published = new AtomicLong();
    private final Duration interval;
    private final Supplier<T> snapshot;
    private final Consumer<T> sink;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "book-feed");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param snapshot takes a fresh snapshot of the book (may block briefly; runs on the feed thread)
     * @param sink     delivers a snapshot to viewers
     */
    BookFeed(Duration interval, Supplier<T> snapshot, Consumer<T> sink) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive, got " + interval);
        }
        this.interval = interval;
        this.snapshot = snapshot;
        this.sink = sink;
    }

    /** Start ticking. The delay is measured from the END of one tick to the start of the next, so two
     *  updates are always at least one interval apart, even if a snapshot is slow. */
    void start() {
        scheduler.scheduleWithFixedDelay(this::tick, interval.toNanos(), interval.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Safe to call from any thread, as often as you like; it only sets a flag. */
    void markChanged() {
        changed.set(true);
    }

    /** How many updates have been sent. */
    long published() {
        return published.get();
    }

    private void tick() {
        if (!changed.getAndSet(false)) {
            return; // nothing happened since the last update: say nothing
        }
        try {
            sink.accept(snapshot.get());
            published.incrementAndGet();
        } catch (RuntimeException e) {
            // e.g. the exchange is shutting down. Do not kill the schedule: the next change retries.
            LOG.debug("could not publish a book update: {}", e.toString());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
