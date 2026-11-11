package com.sanjeev.api;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Feeds {@code /actuator/health}. The exchange is UP while its engine thread runs and DOWN once the
 * sequencer has stopped after a failure (for example the log could not be written). Without this the
 * endpoint would say "UP" for as long as the web server is alive, even with a dead exchange behind it,
 * and a hosting platform would keep sending traffic to it.
 *
 * <p>It asks {@link ExchangeService#failure()}, a plain read: it never waits in the order queue, so a
 * busy exchange is not reported as down just because the check arrived behind thousands of orders. The
 * reason goes to the log only; the HTTP answer carries no details (see application.properties).
 */
@Component
class ExchangeHealthIndicator implements HealthIndicator {

    private static final Logger LOG = LoggerFactory.getLogger(ExchangeHealthIndicator.class);

    private final ExchangeService exchange;

    ExchangeHealthIndicator(ExchangeService exchange) {
        this.exchange = exchange;
    }

    @Override
    public Health health() {
        Optional<String> failure = exchange.failure();
        if (failure.isPresent()) {
            LOG.error("health check: the exchange has stopped: {}", failure.get());
            return Health.down().build();
        }
        return Health.up().build();
    }
}
