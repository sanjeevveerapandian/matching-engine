package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sanjeev.engine.Trade;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TradeFeedTest {

    private TradeFeed feed;

    @AfterEach
    void stop() {
        if (feed != null) {
            feed.close();
        }
    }

    private static Trade trade(long id) {
        return new Trade(id, 1, 2, "Anu", "Ravi", 4000, 1, id);
    }

    private static void awaitCount(List<?> list, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (list.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, list.size());
    }

    @Test
    void everyTradeIsDeliveredInTheOrderItHappened() throws Exception {
        List<Long> received = Collections.synchronizedList(new ArrayList<>());
        feed = new TradeFeed(5_000, t -> received.add(t.tradeId()));
        feed.start();

        for (long id = 1; id <= 2_000; id++) {
            feed.offer(trade(id));
        }

        awaitCount(received, 2_000);
        for (int i = 0; i < 2_000; i++) {
            assertEquals(i + 1L, received.get(i));
        }
        assertEquals(0, feed.dropped());
        assertEquals(2_000, feed.sent());
    }

    @Test
    void offeringNeverMakesTheCallerWaitEvenWhenEveryViewerIsStuck() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<Long> received = Collections.synchronizedList(new ArrayList<>());
        feed = new TradeFeed(5, t -> {
            try {
                release.await(20, TimeUnit.SECONDS); // a viewer that has stopped reading
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            received.add(t.tradeId());
        });
        feed.start();

        Thread engineThread = new Thread(() -> {
            for (long id = 1; id <= 1_000; id++) {
                feed.offer(trade(id)); // this is what the engine thread does for each trade
            }
        });
        engineThread.start();
        engineThread.join(5_000); // it must finish quickly although nothing is being sent

        assertTrue(!engineThread.isAlive(), "the engine thread was blocked by a slow viewer");
        assertTrue(feed.dropped() >= 990, "expected most trades to be dropped, got " + feed.dropped());
        release.countDown();
    }

    @Test
    void tradesThatWereDroppedAreCountedAndTheRestStillArrive() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<Long> received = Collections.synchronizedList(new ArrayList<>());
        feed = new TradeFeed(10, t -> {
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            received.add(t.tradeId());
        });
        feed.start();
        for (long id = 1; id <= 100; id++) {
            feed.offer(trade(id));
        }

        release.countDown();
        long expectedDelivered = 100 - feed.dropped();
        awaitCount(received, (int) expectedDelivered);

        assertTrue(feed.dropped() > 0);
        assertEquals(100, feed.dropped() + received.size());
    }

    @Test
    void aViewerThatFailsDoesNotStopTheFeedForTheNextTrade() throws Exception {
        List<Long> received = Collections.synchronizedList(new ArrayList<>());
        feed = new TradeFeed(100, t -> {
            if (t.tradeId() % 2 == 0) {
                throw new IllegalStateException("connection closed");
            }
            received.add(t.tradeId());
        });
        feed.start();

        for (long id = 1; id <= 10; id++) {
            feed.offer(trade(id));
        }

        awaitCount(received, 5);
        assertEquals(List.of(1L, 3L, 5L, 7L, 9L), List.copyOf(received));
    }

    @Test
    void afterCloseNothingMoreIsDelivered() throws Exception {
        List<Long> received = Collections.synchronizedList(new ArrayList<>());
        feed = new TradeFeed(100, t -> received.add(t.tradeId()));
        feed.start();
        feed.offer(trade(1));
        awaitCount(received, 1);

        feed.close();
        Thread.sleep(100);
        feed.offer(trade(2));
        Thread.sleep(200);

        assertEquals(1, received.size());
    }
}
