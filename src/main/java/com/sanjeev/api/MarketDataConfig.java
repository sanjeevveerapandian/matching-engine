package com.sanjeev.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** Builds the broadcaster that sits between the engine and the WebSocket topics. */
@Configuration
class MarketDataConfig {

    /**
     * The exchange service needs this (to report changes) and this needs the exchange service (to
     * take book snapshots), so the snapshot is looked up lazily, when a tick actually happens.
     */
    @Bean(destroyMethod = "close")
    MarketDataBroadcaster marketDataBroadcaster(SimpMessagingTemplate messaging,
                                                ObjectProvider<ExchangeService> exchange,
                                                MatchingProperties properties) {
        MarketDataBroadcaster broadcaster = new MarketDataBroadcaster(messaging,
                () -> exchange.getObject().book(properties.bookDepth()),
                properties.bookPublishInterval(), properties.tradeFeedCapacity());
        broadcaster.start();
        return broadcaster;
    }

    /** Generates demo traffic through the exchange service, like any other client. */
    @Bean
    TrafficSimulator trafficSimulator(ExchangeService exchange) {
        return new TrafficSimulator(exchange);
    }
}
