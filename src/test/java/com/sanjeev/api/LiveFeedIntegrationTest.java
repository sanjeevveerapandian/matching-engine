package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The live feed end to end: a real server, a real WebSocket, a real STOMP client. Orders are placed
 * over HTTP and the results arrive over the WebSocket, exactly as in the browser.
 *
 * <p>All tests share one running exchange, so each uses its own trader names and price, and cleans
 * up the orders it left behind: that way no test can trade against another's leftovers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveFeedIntegrationTest {

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

    private HttpTestClient http;
    private StompTestSupport stomp;
    private final List<Long> placed = new ArrayList<>();

    @BeforeEach
    void connect() throws Exception {
        http = new HttpTestClient(port);
        stomp = new StompTestSupport(port, broker.getSubscriptionRegistry());
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (long id : placed) {
            http.send("DELETE", "/api/orders/" + id, null); // 404 if it already traded away: fine
        }
        stomp.close();
    }

    private long place(String trader, String side, String price, long quantity) throws Exception {
        HttpTestClient.Reply reply = http.post("/api/orders", HttpTestClient.limitOrder(trader, side, price, quantity));
        assertEquals(201, reply.status(), String.valueOf(reply.body()));
        long id = reply.body().get("orderId").asLong();
        placed.add(id);
        return id;
    }

    /** The next message that satisfies the test, or a failure after 10 seconds. */
    private static <T> T await(BlockingQueue<T> queue, Predicate<T> wanted, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            T message = queue.poll(200, TimeUnit.MILLISECONDS);
            if (message != null && wanted.test(message)) {
                return message;
            }
        }
        throw new AssertionError("no message arrived for: " + what);
    }

    private static boolean hasAsk(Responses.Book book, String price, long quantity) {
        return book.asks().stream().anyMatch(l -> l.price().equals(price) && l.quantity() == quantity);
    }

    private static boolean hasAnyLevelAt(Responses.Book book, String price) {
        return book.asks().stream().anyMatch(l -> l.price().equals(price))
                || book.bids().stream().anyMatch(l -> l.price().equals(price));
    }

    // ---------------------------------------------------------------- the trade feed

    @Test
    void aTradeIsPushedToSubscribersAsSoonAsTwoCrossingOrdersMeet() throws Exception {
        BlockingQueue<TradeView> trades = stomp.subscribe("/topic/trades", TradeView.class);

        place("Ravi1", "SELL", "40.00", 10);
        place("Anu1", "BUY", "40.00", 4);

        TradeView trade = await(trades, t -> t.buyer().equals("Anu1"), "the trade between Anu1 and Ravi1");
        assertEquals("Ravi1", trade.seller());
        assertEquals("40.00", trade.price());   // the resting order's price, as rupee text
        assertEquals(4, trade.quantity());
        assertTrue(trade.tradeId() > 0);
        assertTrue(trade.buyOrderId() > trade.sellOrderId(), "the buy came second");
    }

    @Test
    void nothingIsPushedWhenOrdersDoNotCross() throws Exception {
        BlockingQueue<TradeView> trades = stomp.subscribe("/topic/trades", TradeView.class);

        place("Ravi2", "SELL", "46.00", 10);
        place("Anu2", "BUY", "45.00", 10); // below the ask: no trade

        assertEquals(null, trades.poll(700, TimeUnit.MILLISECONDS));
    }

    @Test
    void everyTradeOfASweepIsPushedSeparatelyInTheOrderItHappened() throws Exception {
        BlockingQueue<TradeView> trades = stomp.subscribe("/topic/trades", TradeView.class);
        place("A3", "SELL", "42.00", 1);
        place("B3", "SELL", "42.00", 1);
        place("C3", "SELL", "42.00", 1);

        place("Sweep3", "BUY", "42.00", 3);

        List<TradeView> sweep = new ArrayList<>();
        while (sweep.size() < 3) {
            sweep.add(await(trades, t -> t.buyer().equals("Sweep3"), "trade " + (sweep.size() + 1) + " of the sweep"));
        }
        assertEquals(List.of("A3", "B3", "C3"), sweep.stream().map(TradeView::seller).toList());
        assertTrue(sweep.get(0).tradeId() < sweep.get(1).tradeId() && sweep.get(1).tradeId() < sweep.get(2).tradeId());
        assertEquals(null, trades.poll(500, TimeUnit.MILLISECONDS), "a fourth trade appeared from nowhere");
    }

    @Test
    void eachViewerReceivesItsOwnCopyOfEveryTrade() throws Exception {
        BlockingQueue<TradeView> first = stomp.subscribe("/topic/trades", TradeView.class);
        try (StompTestSupport second = new StompTestSupport(port, broker.getSubscriptionRegistry())) {
            BlockingQueue<TradeView> other = second.subscribe("/topic/trades", TradeView.class);

            place("Ravi4", "SELL", "47.00", 2);
            place("Anu4", "BUY", "47.00", 2);

            TradeView a = await(first, t -> t.buyer().equals("Anu4"), "trade for the first viewer");
            TradeView b = await(other, t -> t.buyer().equals("Anu4"), "trade for the second viewer");
            assertEquals(a, b);
        }
    }

    // ---------------------------------------------------------------- the book feed

    @Test
    void theBookIsPushedWithTheLatestLaddersAfterTheOrderBookChanges() throws Exception {
        BlockingQueue<Responses.Book> books = stomp.subscribe("/topic/book", Responses.Book.class);

        long id = place("Book5", "SELL", "43.00", 10);

        Responses.Book withOrder = await(books, b -> hasAsk(b, "43.00", 10), "a book showing 10 for sale at 43.00");
        assertNotNull(withOrder.bestAsk());

        assertEquals(200, http.send("DELETE", "/api/orders/" + id, null).status());

        await(books, b -> !hasAnyLevelAt(b, "43.00"), "a book without the 43.00 level after the cancel");
    }

    @Test
    void thePushedBookHasAtMostTenLevelsPerSide() throws Exception {
        BlockingQueue<Responses.Book> books = stomp.subscribe("/topic/book", Responses.Book.class);
        for (int level = 0; level < 14; level++) {
            place("Depth6", "SELL", "60." + String.format("%02d", level), 1);
        }

        Responses.Book book = await(books, b -> b.asks().size() == 10 && b.asks().stream().anyMatch(l -> l.price().equals("60.00")),
                "a ladder capped at ten levels");

        assertEquals(10, book.asks().size());
        assertEquals("60.00", book.asks().get(0).price()); // best (lowest) ask first
    }

    // ---------------------------------------------------------------- the feed is read-only

    @Test
    void aClientCannotPublishAFakeTradeOrAFakeBookToOtherViewers() throws Exception {
        BlockingQueue<TradeView> trades = stomp.subscribe("/topic/trades", TradeView.class);
        BlockingQueue<Responses.Book> books = stomp.subscribe("/topic/book", Responses.Book.class);

        stomp.sendTo("/topic/trades", new TradeView(-1, -1, -1, "Evil", "Mallory", "0.01", 999, -1));
        stomp.sendTo("/topic/book",
                new Responses.Book(List.of(new Responses.Level("0.01", 1)), List.of(), "0.01", null, null));
        place("Ravi7", "SELL", "44.00", 1);
        place("Forge7", "BUY", "44.00", 1);

        TradeView real = await(trades, t -> t.buyer().equals("Forge7"), "the real trade");
        assertEquals("Ravi7", real.seller());
        // keep listening a while: a forged message, if it were relayed, would have arrived by now
        List<TradeView> afterwards = new ArrayList<>();
        TradeView next;
        while ((next = trades.poll(800, TimeUnit.MILLISECONDS)) != null) {
            afterwards.add(next);
        }
        assertFalse(afterwards.stream().anyMatch(t -> t.tradeId() == -1 || t.buyer().equals("Evil")),
                "a forged trade reached the viewers");
        Responses.Book book;
        boolean forgedBook = false;
        while ((book = books.poll(300, TimeUnit.MILLISECONDS)) != null) {
            forgedBook |= hasAnyLevelAt(book, "0.01");
        }
        assertFalse(forgedBook, "a forged book reached the viewers");
    }

    // ---------------------------------------------------------------- the page

    @Test
    void theLivePageIsServedAtTheRootAndLoadsNothingFromOutside() throws Exception {
        HttpTestClient.RawReply page = http.raw("/");

        assertEquals(200, page.status());
        assertTrue(page.contentType().startsWith("text/html"), page.contentType());
        assertTrue(page.body().contains("Matching Engine"));
        assertTrue(page.body().contains("name=\"viewport\""), "must work on a phone");
        assertTrue(page.body().contains("new WebSocket("));
        assertTrue(page.body().contains("/topic/book") && page.body().contains("/topic/trades"));
        assertTrue(page.body().contains("/api/orders") && page.body().contains("/api/simulate"));
        assertFalse(page.body().matches("(?s).*(src|href)=[\"']https?://.*"), "the page must not load anything from other sites");
        assertFalse(page.body().matches("(?s).*(\\.innerHTML|\\.outerHTML|insertAdjacentHTML|document\\.write).*"),
                "text from the server must never be inserted as HTML (a trader named <img onerror=...> would run code)");
        assertEquals(page.body(), http.raw("/index.html").body());
    }

    @Test
    void theErrorsOfTheApiStillComeBackAsJsonNextToTheStaticPage() throws Exception {
        HttpTestClient.Reply reply = http.get("/api/nothing");

        assertEquals(404, reply.status());
        JsonNode body = reply.body();
        assertEquals("Not Found", body.get("error").asText());
    }
}
