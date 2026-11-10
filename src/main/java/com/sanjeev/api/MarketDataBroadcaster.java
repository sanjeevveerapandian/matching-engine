package com.sanjeev.api;

import com.sanjeev.engine.Trade;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Connects the engine to the WebSocket topics:
 *
 * <ul>
 *   <li>{@code /topic/book}: the top levels of the book, at most one update per interval;</li>
 *   <li>{@code /topic/trades}: every trade, as it happens.</li>
 * </ul>
 *
 * <p>The engine thread only talks to the two {@link MarketDataListener} methods, which just leave a
 * note for the feed threads. All the sending happens on those threads, so a slow viewer can never
 * slow the exchange.
 */
final class MarketDataBroadcaster implements MarketDataListener, AutoCloseable {

    static final String BOOK_TOPIC = "/topic/book";
    static final String TRADES_TOPIC = "/topic/trades";

    private final BookFeed<Responses.Book> bookFeed;
    private final TradeFeed tradeFeed;

    /**
     * @param book how to take a snapshot of the book (goes through the sequencer as a query)
     */
    MarketDataBroadcaster(SimpMessagingTemplate messaging, Supplier<Domain.BookSnapshot> book,
                          Duration bookInterval, int tradeCapacity) {
        this.bookFeed = new BookFeed<>(bookInterval,
                () -> Responses.Book.from(book.get()),
                snapshot -> messaging.convertAndSend(BOOK_TOPIC, snapshot));
        this.tradeFeed = new TradeFeed(tradeCapacity,
                trade -> messaging.convertAndSend(TRADES_TOPIC, TradeView.from(trade)));
    }

    void start() {
        bookFeed.start();
        tradeFeed.start();
    }

    @Override
    public void onTrade(Trade trade) {
        tradeFeed.offer(trade);
    }

    @Override
    public void onBookChanged() {
        bookFeed.markChanged();
    }

    /** How many trades were dropped because viewers could not keep up. */
    long droppedTrades() {
        return tradeFeed.dropped();
    }

    @Override
    public void close() {
        bookFeed.close();
        tradeFeed.close();
    }
}
