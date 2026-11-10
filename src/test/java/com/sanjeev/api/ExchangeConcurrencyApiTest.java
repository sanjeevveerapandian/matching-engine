package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Many clients at the same time over real HTTP: the single engine thread must keep everything straight. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExchangeConcurrencyApiTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("matching.data-dir", () -> dataDir.toString());
    }

    @LocalServerPort
    int port;

    @Test
    void eightClientsPlacingMatchingBuysAndSellsAtOnceEndWithEveryUnitTradedExactlyOnce() throws Exception {
        HttpTestClient http = new HttpTestClient(port);
        int clients = 8; // 4 only sell, 4 only buy
        int perClient = 50;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch go = new CountDownLatch(1);
        Set<Long> orderIds = Collections.synchronizedSet(new HashSet<>());
        Set<Long> sequences = Collections.synchronizedSet(new HashSet<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int c = 0; c < clients; c++) {
            String side = c % 2 == 0 ? "SELL" : "BUY";
            String name = "client" + c;
            futures.add(pool.submit(() -> {
                try {
                    go.await();
                    for (int i = 0; i < perClient; i++) {
                        HttpTestClient.Reply reply = http.post("/api/orders",
                                HttpTestClient.limitOrder(name, side, "40.00", 1));
                        assertEquals(201, reply.status(), String.valueOf(reply.body()));
                        orderIds.add(reply.body().get("orderId").asLong());
                        sequences.add(reply.body().get("sequence").asLong());
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            }));
        }
        go.countDown();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertTrue(errors.isEmpty(), "errors: " + errors);
        int total = clients * perClient;
        assertEquals(total, orderIds.size(), "every order id must be unique");
        assertEquals(total, sequences.size(), "every sequence number must be unique");
        for (long expected = 1; expected <= total; expected++) {
            assertTrue(sequences.contains(expected), "sequence " + expected + " is missing: not gap-free");
        }

        // 200 units sold, 200 units bought, same price: nothing can be left over, and every order
        // (quantity 1) traded exactly once
        JsonNode book = http.get("/api/book?depth=10").body();
        assertEquals(0, book.get("bids").size());
        assertEquals(0, book.get("asks").size());
        JsonNode trades = http.get("/api/trades?limit=500").body();
        assertEquals(total / 2, trades.size());
        Set<Long> buyers = new HashSet<>();
        Set<Long> sellers = new HashSet<>();
        for (JsonNode trade : trades) {
            assertEquals(1, trade.get("quantity").asLong());
            assertEquals("40.00", trade.get("price").asText());
            buyers.add(trade.get("buyOrderId").asLong());
            sellers.add(trade.get("sellOrderId").asLong());
        }
        assertEquals(total / 2, buyers.size(), "a buy order traded twice");
        assertEquals(total / 2, sellers.size(), "a sell order traded twice");

        // and the log holds every command, in sequence order
        Path run;
        try (Stream<Path> runs = Files.list(dataDir)) {
            run = runs.filter(p -> p.getFileName().toString().startsWith("run-")).findFirst().orElseThrow();
        }
        List<String> log = readLog(run);
        assertEquals(total, log.size());
        for (int i = 0; i < log.size(); i++) {
            assertTrue(log.get(i).startsWith("{\"sequence\":" + (i + 1) + ","), "line " + (i + 1) + " out of order");
        }
    }

    private static List<String> readLog(Path run) throws IOException {
        return Files.readAllLines(run.resolve("commands.log"));
    }
}
