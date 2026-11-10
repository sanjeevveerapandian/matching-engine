package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Hammer the exchange with thousands of orders and watch what the viewers receive: far fewer book
 * updates than orders (the throttle), the final book exactly right, and every trade delivered in order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookThrottleIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("matching.data-dir", () -> dataDir.toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    SimpleBrokerMessageHandler broker; // the server's own subscription records

    private final ObjectMapper mapper = new ObjectMapper();

    private static void drainUntilQuiet(BlockingQueue<?> queue, long quietMillis, List<Object> into) throws Exception {
        Object message;
        while ((message = queue.poll(quietMillis, TimeUnit.MILLISECONDS)) != null) {
            into.add(message);
        }
    }

    @Test
    void thousandsOfOrdersBecomeAFewBookUpdatesAndTheLastOneIsTheFinalBook() throws Exception {
        HttpTestClient http = new HttpTestClient(port);
        try (StompTestSupport stomp = new StompTestSupport(port, broker.getSubscriptionRegistry())) {
            BlockingQueue<Responses.Book> books = stomp.subscribe("/topic/book", Responses.Book.class);

            long start = System.nanoTime();
            HttpTestClient.Reply simulation = http.post("/api/simulate?count=3000&seed=1", null);
            assertEquals(200, simulation.status(), String.valueOf(simulation.body()));
            List<Object> received = new ArrayList<>();
            drainUntilQuiet(books, 600, received); // wait until the feed goes quiet
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            int updates = received.size();
            long upperBound = elapsedMillis / 100 + 3; // one per 100 ms, plus a little slack
            assertEquals(3000, simulation.body().get("requested").asInt());
            assertTrue(updates >= 1, "the viewers saw nothing");
            assertTrue(updates <= upperBound,
                    updates + " book updates in " + elapsedMillis + " ms is more than one per 100 ms");
            assertTrue(updates * 10 < 3000, "3000 orders should become far fewer updates, got " + updates);

            // the LAST update must be the final state of the book
            Responses.Book last = (Responses.Book) received.get(received.size() - 1);
            JsonNode pushed = mapper.readTree(mapper.writeValueAsString(last)); // same number types as the REST body
            JsonNode rest = http.get("/api/book?depth=10").body();
            assertEquals(rest, pushed, "the final pushed book differs from the real book");
        }
    }

    @Test
    void everyTradeOfASimulationIsPushedExactlyOnceAndInOrder() throws Exception {
        HttpTestClient http = new HttpTestClient(port);
        try (StompTestSupport stomp = new StompTestSupport(port, broker.getSubscriptionRegistry())) {
            BlockingQueue<TradeView> trades = stomp.subscribe("/topic/trades", TradeView.class);
            JsonNode before = http.get("/api/trades?limit=1").body();
            long lastTradeIdBefore = before.size() == 0 ? 0 : before.get(0).get("tradeId").asLong();

            HttpTestClient.Reply simulation = http.post("/api/simulate?count=1500&seed=3", null);
            assertEquals(200, simulation.status(), String.valueOf(simulation.body()));
            int expected = simulation.body().get("trades").asInt();
            assertTrue(expected > 50, "the simulation should cause trades, got " + expected);

            List<Long> ids = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (ids.size() < expected && System.nanoTime() < deadline) {
                TradeView trade = trades.poll(200, TimeUnit.MILLISECONDS);
                if (trade != null && trade.tradeId() > lastTradeIdBefore) {
                    ids.add(trade.tradeId());
                }
            }

            assertEquals(expected, ids.size(), "trades went missing between the engine and the viewers");
            for (int i = 1; i < ids.size(); i++) {
                assertEquals(ids.get(i - 1) + 1, ids.get(i).longValue(),
                        "trade ids must arrive consecutively, in order; got " + ids.subList(Math.max(0, i - 3), Math.min(ids.size(), i + 3)));
            }
        }
    }

    @Test
    void aSimulationReportsWhatItDidAndLeavesAValidBook() throws Exception {
        HttpTestClient http = new HttpTestClient(port);

        HttpTestClient.Reply simulation = http.post("/api/simulate?count=500&seed=7", null);

        assertEquals(200, simulation.status());
        JsonNode body = simulation.body();
        assertEquals(500, body.get("requested").asInt());
        assertEquals(500, body.get("limitOrders").asInt() + body.get("marketOrders").asInt()
                + body.get("cancels").asInt() + body.get("modifies").asInt());
        assertEquals(7, body.get("seed").asLong());
        JsonNode book = http.get("/api/book?depth=10").body();
        if (!book.get("bestBid").isNull() && !book.get("bestAsk").isNull()) {
            assertTrue(new BigDecimal(book.get("bestBid").asText()).compareTo(new BigDecimal(book.get("bestAsk").asText())) < 0,
                    "the book is crossed: " + book);
        }
    }

    @Test
    void aBadSimulationRequestIsRefusedWithoutTouchingTheExchange() throws Exception {
        HttpTestClient http = new HttpTestClient(port);
        long before = http.get("/api/trades?limit=1").body().size();

        assertEquals(400, http.post("/api/simulate?count=0", null).status());
        assertEquals(400, http.post("/api/simulate?count=10001", null).status());
        assertEquals(400, http.post("/api/simulate?count=abc", null).status());

        assertEquals(before, http.get("/api/trades?limit=1").body().size());
    }
}
