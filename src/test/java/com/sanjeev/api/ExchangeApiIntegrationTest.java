package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole application, started for real: Tomcat on a random port, the Spring context, the
 * sequencer thread, the command log. A client places a sell and a buy over HTTP and sees the trade.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExchangeApiIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("matching.data-dir", () -> dataDir.toString());
        registry.add("matching.snapshot-every", () -> "3");
    }

    @LocalServerPort
    int port;

    private static Path onlyRunDirectory() throws IOException {
        try (Stream<Path> runs = Files.list(dataDir)) {
            List<Path> found = runs.filter(p -> p.getFileName().toString().startsWith("run-")).toList();
            assertEquals(1, found.size(), "expected exactly one run folder, got " + found);
            return found.get(0);
        }
    }

    @Test
    void aSellAndABuyMeetOverHttpAndTheTradeShowsUpEverywhere() throws Exception {
        HttpTestClient http = new HttpTestClient(port);

        // 1. Ravi puts 10 tomatoes up for sale at Rs 40.00. Nothing to match: it rests.
        HttpTestClient.Reply sell = http.post("/api/orders", HttpTestClient.limitOrder("Ravi", "SELL", "40.00", 10));
        assertEquals(201, sell.status());
        assertEquals(1, sell.body().get("orderId").asLong());
        assertEquals("RESTING", sell.body().get("status").asText());

        // 2. Anu wants 4 at Rs 40.00. They meet: a trade, at the resting order's price.
        HttpTestClient.Reply buy = http.post("/api/orders", HttpTestClient.limitOrder("Anu", "BUY", "40.00", 4));
        assertEquals(201, buy.status());
        assertEquals("FILLED", buy.body().get("status").asText());
        assertEquals(4, buy.body().get("filledQuantity").asLong());
        assertEquals(1, buy.body().get("trades").size());
        assertEquals("40.00", buy.body().get("trades").get(0).get("price").asText());
        assertEquals(2, buy.body().get("sequence").asLong()); // the second command through the sequencer

        // 3. The trade is in the recent trades, with names and rupee prices.
        HttpTestClient.Reply trades = http.get("/api/trades");
        assertEquals(200, trades.status());
        assertEquals(1, trades.body().size());
        assertEquals("Anu", trades.body().get(0).get("buyer").asText());
        assertEquals("Ravi", trades.body().get(0).get("seller").asText());
        assertEquals("40.00", trades.body().get(0).get("price").asText());
        assertEquals(4, trades.body().get(0).get("quantity").asLong());
        assertEquals(2, trades.body().get(0).get("buyOrderId").asLong());
        assertEquals(1, trades.body().get(0).get("sellOrderId").asLong());

        // 4. The book shows what is left of Ravi's order: 6 for sale at 40.00, and no buyers.
        HttpTestClient.Reply book = http.get("/api/book?depth=5");
        assertEquals(200, book.status());
        assertEquals("40.00", book.body().get("asks").get(0).get("price").asText());
        assertEquals(6, book.body().get("asks").get(0).get("quantity").asLong());
        assertEquals(0, book.body().get("bids").size());
        assertEquals("40.00", book.body().get("bestAsk").asText());
        assertTrue(book.body().get("bestBid").isNull());
        assertTrue(book.body().get("spread").isNull());

        // 5. Ravi shrinks his order from 6 to 3 at the same price: he keeps his place in the queue.
        HttpTestClient.Reply modify = http.send("PATCH", "/api/orders/1", "{\"price\":\"40.00\",\"quantity\":3}");
        assertEquals(200, modify.status());
        assertTrue(modify.body().get("keptPriority").asBoolean());
        assertEquals(3, http.get("/api/book").body().get("asks").get(0).get("quantity").asLong());

        // 6. A request that fails validation is refused at the edge and never reaches the log.
        HttpTestClient.Reply bad = http.post("/api/orders", HttpTestClient.limitOrder("Anu", "BUY", "40.505", 1));
        assertEquals(400, bad.status());
        assertTrue(bad.body().get("message").asText().contains("at most 2 decimal places"));

        // 7. Ravi cancels what is left: 3 units.
        HttpTestClient.Reply cancel = http.send("DELETE", "/api/orders/1", null);
        assertEquals(200, cancel.status());
        assertEquals(3, cancel.body().get("cancelledQuantity").asLong());
        assertEquals(0, http.get("/api/book").body().get("asks").size());

        // 8. Cancelling it again: it is no longer resting.
        HttpTestClient.Reply again = http.send("DELETE", "/api/orders/1", null);
        assertEquals(404, again.status());
        assertEquals("Not Found", again.body().get("error").asText());

        // Every request that reached the engine went through the sequencer and into the log, in order:
        // sell, buy, modify, cancel, and the refused second cancel. The reads and the 400 are not there.
        Path runDirectory = onlyRunDirectory();
        List<String> log = Files.readAllLines(runDirectory.resolve("commands.log"));
        assertEquals(5, log.size(), String.join("\n", log));
        assertTrue(log.get(0).startsWith("{\"sequence\":1,"));
        assertTrue(log.get(0).contains("\"priceInPaise\":4000"), "rupees must be paise inside: " + log.get(0));
        assertTrue(log.get(1).contains("\"trader\":\"Anu\""));
        assertTrue(log.get(2).contains("\"kind\":\"ModifyOrder\""));
        assertTrue(log.get(3).contains("\"kind\":\"CancelOrder\""));
        assertTrue(log.get(4).startsWith("{\"sequence\":5,"));

        // snapshots were configured to be taken every 3 commands
        assertTrue(Files.exists(runDirectory.resolve("snapshots").resolve("snapshot-3.json")));
    }
}
