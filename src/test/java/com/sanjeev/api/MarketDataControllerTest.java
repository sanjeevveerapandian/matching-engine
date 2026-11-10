package com.sanjeev.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sanjeev.engine.OrderBook;
import com.sanjeev.engine.Trade;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MarketDataController.class)
class MarketDataControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ExchangeService exchange;

    // ---------------------------------------------------------------- GET /api/book

    @Test
    void theBookIsShownWithRupeePricesBestFirstAndTheSpread() throws Exception {
        when(exchange.book(10)).thenReturn(new Domain.BookSnapshot(
                List.of(new OrderBook.Level(4000, 120), new OrderBook.Level(3950, 30)),
                List.of(new OrderBook.Level(4050, 10), new OrderBook.Level(4100, 7)),
                4000L, 4050L));

        mvc.perform(get("/api/book"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bids[0].price").value("40.00"))
                .andExpect(jsonPath("$.bids[0].quantity").value(120))
                .andExpect(jsonPath("$.bids[1].price").value("39.50"))
                .andExpect(jsonPath("$.asks[0].price").value("40.50"))
                .andExpect(jsonPath("$.asks[1].quantity").value(7))
                .andExpect(jsonPath("$.bestBid").value("40.00"))
                .andExpect(jsonPath("$.bestAsk").value("40.50"))
                .andExpect(jsonPath("$.spread").value("0.50"));
    }

    @Test
    void anEmptyBookHasEmptyLaddersAndNoBestPricesOrSpread() throws Exception {
        when(exchange.book(10)).thenReturn(new Domain.BookSnapshot(List.of(), List.of(), null, null));

        mvc.perform(get("/api/book"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bids.length()").value(0))
                .andExpect(jsonPath("$.asks.length()").value(0))
                .andExpect(jsonPath("$.bestBid").value(nullValue()))
                .andExpect(jsonPath("$.bestAsk").value(nullValue()))
                .andExpect(jsonPath("$.spread").value(nullValue()));
    }

    @Test
    void oneSidedBookHasNoSpread() throws Exception {
        when(exchange.book(10)).thenReturn(new Domain.BookSnapshot(
                List.of(new OrderBook.Level(4000, 1)), List.of(), 4000L, null));

        mvc.perform(get("/api/book"))
                .andExpect(jsonPath("$.bestBid").value("40.00"))
                .andExpect(jsonPath("$.bestAsk").value(nullValue()))
                .andExpect(jsonPath("$.spread").value(nullValue()));
    }

    @Test
    void theDepthParameterIsPassedOn() throws Exception {
        when(exchange.book(3)).thenReturn(new Domain.BookSnapshot(List.of(), List.of(), null, null));

        mvc.perform(get("/api/book").param("depth", "3")).andExpect(status().isOk());

        verify(exchange).book(3);
    }

    @ParameterizedTest(name = "depth {0} is accepted")
    @ValueSource(strings = {"1", "100"})
    void theDepthLimitsThemselvesAreAccepted(String depth) throws Exception {
        when(exchange.book(Integer.parseInt(depth))).thenReturn(new Domain.BookSnapshot(List.of(), List.of(), null, null));

        mvc.perform(get("/api/book").param("depth", depth)).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "depth \"{0}\" is a 400")
    @ValueSource(strings = {"0", "-1", "101", "abc", "2.5", "99999999999999999999"})
    void aBadDepthIsAnswered400(String depth) throws Exception {
        mvc.perform(get("/api/book").param("depth", depth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(exchange);
    }

    @Test
    void anEmptyDepthMeansTheDefaultJustLikeALeftOutOne() throws Exception {
        when(exchange.book(10)).thenReturn(new Domain.BookSnapshot(List.of(), List.of(), null, null));

        mvc.perform(get("/api/book").param("depth", "")).andExpect(status().isOk());

        verify(exchange).book(10);
    }

    @Test
    void anOutOfRangeDepthSaysWhatTheRangeIs() throws Exception {
        mvc.perform(get("/api/book").param("depth", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("between 1 and 100")))
                .andExpect(jsonPath("$.details[0].field").value("depth"));
    }

    @Test
    void theBookIsAnswered503IfTheExchangeCannotAnswer() throws Exception {
        when(exchange.book(10)).thenThrow(new ExchangeUnavailableException("the exchange is not accepting requests", null));

        mvc.perform(get("/api/book")).andExpect(status().isServiceUnavailable());
    }

    // ---------------------------------------------------------------- GET /api/trades

    @Test
    void recentTradesAreListedNewestFirstWithRupeePrices() throws Exception {
        when(exchange.recentTrades(50)).thenReturn(List.of(
                new Trade(2, 4, 3, "Anu", "Priya", 4050, 5, 9),
                new Trade(1, 2, 1, "Anu", "Ravi", 5, 1, 7)));

        mvc.perform(get("/api/trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].tradeId").value(2))
                .andExpect(jsonPath("$[0].price").value("40.50"))
                .andExpect(jsonPath("$[0].buyer").value("Anu"))
                .andExpect(jsonPath("$[0].seller").value("Priya"))
                .andExpect(jsonPath("$[0].quantity").value(5))
                .andExpect(jsonPath("$[1].price").value("0.05"));
    }

    @Test
    void noTradesGivesAnEmptyList() throws Exception {
        when(exchange.recentTrades(50)).thenReturn(List.of());

        mvc.perform(get("/api/trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theLimitParameterIsPassedOn() throws Exception {
        when(exchange.recentTrades(7)).thenReturn(List.of());

        mvc.perform(get("/api/trades").param("limit", "7")).andExpect(status().isOk());

        verify(exchange).recentTrades(7);
    }

    @ParameterizedTest(name = "limit {0} is accepted")
    @ValueSource(strings = {"1", "500"})
    void theLimitBoundsThemselvesAreAccepted(String limit) throws Exception {
        when(exchange.recentTrades(Integer.parseInt(limit))).thenReturn(List.of());

        mvc.perform(get("/api/trades").param("limit", limit)).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "limit \"{0}\" is a 400")
    @ValueSource(strings = {"0", "-5", "501", "abc", "1.5"})
    void aBadLimitIsAnswered400(String limit) throws Exception {
        mvc.perform(get("/api/trades").param("limit", limit))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(exchange);
    }

    @Test
    void anEmptyLimitMeansTheDefaultJustLikeALeftOutOne() throws Exception {
        when(exchange.recentTrades(50)).thenReturn(List.of());

        mvc.perform(get("/api/trades").param("limit", "")).andExpect(status().isOk());

        verify(exchange).recentTrades(50);
    }

    @Test
    void anOutOfRangeLimitSaysWhatTheRangeIs() throws Exception {
        mvc.perform(get("/api/trades").param("limit", "9999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("between 1 and 500")));
    }
}
