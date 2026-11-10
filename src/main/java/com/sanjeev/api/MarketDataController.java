package com.sanjeev.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only views of the exchange: the order book ladder and the most recent trades. */
@RestController
@RequestMapping("/api")
class MarketDataController {

    static final int MAX_DEPTH = 100;
    static final int MAX_TRADES = 500;

    private final ExchangeService exchange;

    MarketDataController(ExchangeService exchange) {
        this.exchange = exchange;
    }

    /** The best {@code depth} price levels on each side, best first. */
    @GetMapping("/book")
    Responses.Book book(@RequestParam(name = "depth", defaultValue = "10") int depth) {
        requireBetween("depth", depth, MAX_DEPTH);
        return Responses.Book.from(exchange.book(depth));
    }

    /** The most recent trades, newest first. */
    @GetMapping("/trades")
    List<TradeView> trades(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        requireBetween("limit", limit, MAX_TRADES);
        return exchange.recentTrades(limit).stream().map(TradeView::from).toList();
    }

    private static void requireBetween(String name, int value, int max) {
        if (value < 1 || value > max) {
            throw new InvalidRequestException(name, name + " must be between 1 and " + max);
        }
    }
}
