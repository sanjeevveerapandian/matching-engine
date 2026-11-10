package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.sanjeev.engine.OrderBook;
import com.sanjeev.engine.Trade;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** What gets sent to which topic, with the network replaced by a recording stand-in. */
class MarketDataBroadcasterTest {

    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private MarketDataBroadcaster broadcaster;

    @AfterEach
    void stop() {
        if (broadcaster != null) {
            broadcaster.close();
        }
    }

    private MarketDataBroadcaster start(Domain.BookSnapshot book) {
        broadcaster = new MarketDataBroadcaster(messaging, () -> book, Duration.ofMillis(20), 100);
        broadcaster.start();
        return broadcaster;
    }

    @Test
    void aTradeIsSentToTheTradesTopicWithARupeePrice() {
        start(new Domain.BookSnapshot(List.of(), List.of(), null, null));

        broadcaster.onTrade(new Trade(5, 2, 1, "Anu", "Ravi", 4050, 4, 9));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messaging, timeout(5_000)).convertAndSend(eq("/topic/trades"), payload.capture());
        TradeView sent = assertInstanceOf(TradeView.class, payload.getValue());
        assertEquals(5, sent.tradeId());
        assertEquals("40.50", sent.price());
        assertEquals("Anu", sent.buyer());
    }

    @Test
    void aBookChangeSendsTheCurrentLaddersToTheBookTopicWithRupeePrices() {
        start(new Domain.BookSnapshot(
                List.of(new OrderBook.LevelSummary(4000, 120)), List.of(new OrderBook.LevelSummary(4050, 10)), 4000L, 4050L));

        broadcaster.onBookChanged();

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messaging, timeout(5_000)).convertAndSend(eq("/topic/book"), payload.capture());
        Responses.Book sent = assertInstanceOf(Responses.Book.class, payload.getValue());
        assertEquals("40.00", sent.bids().get(0).price());
        assertEquals(120, sent.bids().get(0).quantity());
        assertEquals("40.50", sent.asks().get(0).price());
        assertEquals("0.50", sent.spread());
    }

    @Test
    void tradesDroppedBecauseViewersFellBehindAreCounted() {
        broadcaster = new MarketDataBroadcaster(messaging, () -> null, Duration.ofSeconds(60), 1);
        // not started: nothing drains the queue, so it fills up after one trade

        for (long id = 1; id <= 5; id++) {
            broadcaster.onTrade(new Trade(id, 1, 2, "Anu", "Ravi", 4000, 1, id));
        }

        assertEquals(4, broadcaster.droppedTrades());
    }
}
