package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * The health check must tell the truth about the exchange: UP while the engine runs, DOWN once it has
 * stopped after a failure, so a host's health probe stops sending traffic to a dead exchange.
 */
class ExchangeHealthIndicatorTest {

    @Test
    void theExchangeIsUpWhileItHasNotFailed() {
        ExchangeService exchange = mock(ExchangeService.class);
        when(exchange.failure()).thenReturn(Optional.empty());

        Health health = new ExchangeHealthIndicator(exchange).health();

        assertEquals(Status.UP, health.getStatus());
    }

    @Test
    void theExchangeIsDownOnceTheEngineHasStoppedAfterAFailure() {
        ExchangeService exchange = mock(ExchangeService.class);
        when(exchange.failure()).thenReturn(Optional.of("disk full"));

        Health health = new ExchangeHealthIndicator(exchange).health();

        assertEquals(Status.DOWN, health.getStatus());
    }

    @Test
    void theCheckNeverQueuesBehindOrdersSoABusyExchangeIsNotReportedDown() {
        ExchangeService exchange = mock(ExchangeService.class);
        when(exchange.failure()).thenReturn(Optional.empty());

        new ExchangeHealthIndicator(exchange).health();

        verify(exchange).failure();           // the only question it asks: a plain read, no queue involved
        verifyNoMoreInteractions(exchange);   // in particular it never calls book(): that would wait in line
    }

    @Test
    void theFailureReasonStaysInTheLogAndIsNotPartOfTheHealthAnswer() {
        ExchangeService exchange = mock(ExchangeService.class);
        when(exchange.failure()).thenReturn(Optional.of("java.io.IOException: /secret/path/commands.log"));

        Health health = new ExchangeHealthIndicator(exchange).health();

        assertTrue(health.getDetails().isEmpty(), "details would be exposed to anyone who can call the endpoint");
    }
}
