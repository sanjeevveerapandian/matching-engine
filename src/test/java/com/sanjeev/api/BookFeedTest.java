package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The throttle, tested with real (short) intervals. Everything that says "at most" is a safe
 * upper bound; the "eventually" checks wait with generous timeouts, so a slow machine cannot
 * make them fail.
 */
class BookFeedTest {

    private BookFeed<Integer> feed;

    @AfterEach
    void stop() {
        if (feed != null) {
            feed.close();
        }
    }

    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    @Test
    void nothingIsPublishedWhenNothingHasChanged() throws Exception {
        BlockingQueue<Integer> sent = new LinkedBlockingQueue<>();
        feed = new BookFeed<>(Duration.ofMillis(20), () -> 1, sent::add);
        feed.start();

        sleep(250);

        assertTrue(sent.isEmpty());
        assertEquals(0, feed.published());
    }

    @Test
    void aChangeIsPublishedSoonAfterwardsWithAFreshSnapshot() throws Exception {
        BlockingQueue<Integer> sent = new LinkedBlockingQueue<>();
        AtomicInteger state = new AtomicInteger(7);
        feed = new BookFeed<>(Duration.ofMillis(20), state::get, sent::add);
        feed.start();

        feed.markChanged();

        assertEquals(7, sent.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void tenThousandChangesInsideOneIntervalBecomeOneUpdate() throws Exception {
        BlockingQueue<Integer> sent = new LinkedBlockingQueue<>();
        feed = new BookFeed<>(Duration.ofMillis(300), () -> 1, sent::add);
        feed.start();

        for (int i = 0; i < 10_000; i++) {
            feed.markChanged();
        }
        sleep(800); // two more ticks pass, with nothing new to say

        assertEquals(1, sent.size());
    }

    @Test
    void updatesAreNeverCloserTogetherThanTheInterval() throws Exception {
        long intervalMillis = 50;
        List<Long> times = Collections.synchronizedList(new ArrayList<>());
        feed = new BookFeed<>(Duration.ofMillis(intervalMillis), () -> 1, snapshot -> times.add(System.nanoTime()));
        feed.start();

        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700);
        while (System.nanoTime() < end) {
            feed.markChanged(); // the book changes constantly, as fast as we can
        }
        sleep(150);

        List<Long> copy = List.copyOf(times);
        assertTrue(copy.size() >= 2, "expected several updates, got " + copy.size());
        for (int i = 1; i < copy.size(); i++) {
            long gapMillis = TimeUnit.NANOSECONDS.toMillis(copy.get(i) - copy.get(i - 1));
            assertTrue(gapMillis >= intervalMillis - 2, "two updates only " + gapMillis + " ms apart");
        }
        assertTrue(copy.size() <= 700 / intervalMillis + 3, "too many updates: " + copy.size());
    }

    @Test
    void whenABurstEndsTheFinalStateIsStillPublished() throws Exception {
        BlockingQueue<Integer> sent = new LinkedBlockingQueue<>();
        AtomicInteger state = new AtomicInteger();
        feed = new BookFeed<>(Duration.ofMillis(30), state::get, sent::add);
        feed.start();

        for (int i = 1; i <= 5_000; i++) {
            state.set(i);
            feed.markChanged();
        }

        Integer last = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && (last == null || last != 5_000)) {
            Integer next = sent.poll(100, TimeUnit.MILLISECONDS);
            if (next != null) {
                last = next;
            }
        }
        assertEquals(5_000, last, "the last thing viewers see must be the final state");
    }

    @Test
    void aSnapshotThatFailsDoesNotStopTheFeed() throws Exception {
        BlockingQueue<Integer> sent = new LinkedBlockingQueue<>();
        AtomicInteger calls = new AtomicInteger();
        feed = new BookFeed<>(Duration.ofMillis(20), () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("the exchange is busy shutting down");
            }
            return 42;
        }, sent::add);
        feed.start();

        feed.markChanged();
        sleep(200); // first tick fails
        assertNull(sent.poll());
        feed.markChanged();

        assertEquals(42, sent.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void afterCloseNothingMoreIsPublished() throws Exception {
        BlockingQueue<Integer> sent = new LinkedBlockingQueue<>();
        feed = new BookFeed<>(Duration.ofMillis(20), () -> 1, sent::add);
        feed.start();
        feed.markChanged();
        assertNotNull(sent.poll(5, TimeUnit.SECONDS));

        feed.close();
        feed.markChanged();
        sleep(200);

        assertTrue(sent.isEmpty());
    }

    @Test
    void theIntervalMustBeAPositiveDuration() {
        assertThrows(IllegalArgumentException.class, () -> new BookFeed<>(Duration.ZERO, () -> 1, x -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> new BookFeed<>(Duration.ofMillis(-5), () -> 1, x -> { }));
    }

    @Test
    void thePublishedCountMatchesWhatWasSent() throws Exception {
        BlockingQueue<Integer> sent = new LinkedBlockingQueue<>();
        feed = new BookFeed<>(Duration.ofMillis(20), () -> 1, sent::add);
        feed.start();
        feed.markChanged();
        assertNotNull(sent.poll(5, TimeUnit.SECONDS));
        sleep(50);

        assertEquals(1, feed.published());
    }
}
