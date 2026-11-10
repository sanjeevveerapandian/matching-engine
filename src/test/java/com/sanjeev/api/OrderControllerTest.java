package com.sanjeev.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sanjeev.engine.OrderType;
import com.sanjeev.engine.Side;
import com.sanjeev.engine.TimeInForce;
import com.sanjeev.engine.Trade;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Only the web layer is loaded; the exchange is a stand-in, so these tests are about HTTP alone. */
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ExchangeService exchange;

    private static final Domain.PlacedOrder RESTING = new Domain.PlacedOrder(17, 5, 10, 0, 10, 0, List.of());

    private ResultActions postOrder(String json) throws Exception {
        return mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** Builds a request body from raw JSON fragments; a null fragment leaves the field out. */
    private static String order(String trader, String side, String type, String price, String quantity, String tif) {
        StringBuilder json = new StringBuilder("{");
        append(json, "trader", trader);
        append(json, "side", side);
        append(json, "type", type);
        append(json, "price", price);
        append(json, "quantity", quantity);
        append(json, "timeInForce", tif);
        return json.append("}").toString();
    }

    private static void append(StringBuilder json, String name, String rawJson) {
        if (rawJson != null) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('"').append(name).append("\":").append(rawJson);
        }
    }

    private static String validLimit() {
        return order("\"Ravi\"", "\"SELL\"", "\"LIMIT\"", "\"40.50\"", "10", "\"GTC\"");
    }

    private Domain.NewOrderSpec specSentToTheExchange() {
        ArgumentCaptor<Domain.NewOrderSpec> captor = ArgumentCaptor.forClass(Domain.NewOrderSpec.class);
        verify(exchange).placeOrder(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- POST /api/orders: the happy path

    @Test
    void aLimitOrderIsCreatedAndItsRupeePriceReachesTheExchangeAsPaise() throws Exception {
        when(exchange.placeOrder(any())).thenReturn(RESTING);

        postOrder(validLimit())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(17))
                .andExpect(jsonPath("$.sequence").value(5))
                .andExpect(jsonPath("$.status").value("RESTING"))
                .andExpect(jsonPath("$.restingQuantity").value(10));

        Domain.NewOrderSpec spec = specSentToTheExchange();
        assertEquals("Ravi", spec.trader());
        assertEquals(Side.SELL, spec.side());
        assertEquals(OrderType.LIMIT, spec.type());
        assertEquals(TimeInForce.GTC, spec.timeInForce());
        assertEquals(4050L, spec.pricePaise());
        assertEquals(10, spec.quantity());
    }

    @ParameterizedTest(name = "price \"{0}\" reaches the exchange as {1} paise")
    @CsvSource({"40, 4000", "40.5, 4050", "40.50, 4050", "40.500, 4050", "0.01, 1", "19.99, 1999",
            "100000000.00, 10000000000"})
    void variousWaysOfWritingAPriceAreConvertedExactly(String price, long expectedPaise) throws Exception {
        when(exchange.placeOrder(any())).thenReturn(RESTING);

        postOrder(order("\"Ravi\"", "\"SELL\"", "\"LIMIT\"", "\"" + price + "\"", "10", null))
                .andExpect(status().isCreated());

        assertEquals(expectedPaise, specSentToTheExchange().pricePaise());
    }

    @Test
    void theTimeInForceDefaultsToGoodTillCancelledForALimitOrder() throws Exception {
        when(exchange.placeOrder(any())).thenReturn(RESTING);

        postOrder(order("\"Ravi\"", "\"SELL\"", "\"LIMIT\"", "\"40.50\"", "10", null)).andExpect(status().isCreated());

        assertEquals(TimeInForce.GTC, specSentToTheExchange().timeInForce());
    }

    @ParameterizedTest
    @ValueSource(strings = {"IOC", "FOK", "GTC"})
    void theRequestedTimeInForceIsPassedOn(String tif) throws Exception {
        when(exchange.placeOrder(any())).thenReturn(RESTING);

        postOrder(order("\"Ravi\"", "\"SELL\"", "\"LIMIT\"", "\"40.50\"", "10", "\"" + tif + "\""))
                .andExpect(status().isCreated());

        assertEquals(TimeInForce.valueOf(tif), specSentToTheExchange().timeInForce());
    }

    @Test
    void aMarketOrderNeedsNoPriceAndIsImmediateOrCancel() throws Exception {
        when(exchange.placeOrder(any())).thenReturn(RESTING);

        postOrder(order("\"Anu\"", "\"BUY\"", "\"MARKET\"", null, "5", null)).andExpect(status().isCreated());

        Domain.NewOrderSpec spec = specSentToTheExchange();
        assertEquals(OrderType.MARKET, spec.type());
        assertEquals(null, spec.pricePaise());
        assertEquals(TimeInForce.IOC, spec.timeInForce());
    }

    @Test
    void aMarketOrderMayStateImmediateOrCancelExplicitly() throws Exception {
        when(exchange.placeOrder(any())).thenReturn(RESTING);

        postOrder(order("\"Anu\"", "\"BUY\"", "\"MARKET\"", null, "5", "\"IOC\"")).andExpect(status().isCreated());
    }

    @Test
    void enumValuesAreAcceptedInAnyLetterCase() throws Exception {
        when(exchange.placeOrder(any())).thenReturn(RESTING);

        postOrder(order("\"Ravi\"", "\"sell\"", "\"limit\"", "\"40.50\"", "10", "\"gtc\"")).andExpect(status().isCreated());

        assertEquals(Side.SELL, specSentToTheExchange().side());
    }

    @Test
    void theTraderNameIsTrimmed() throws Exception {
        when(exchange.placeOrder(any())).thenReturn(RESTING);

        postOrder(order("\"  Ravi  \"", "\"SELL\"", "\"LIMIT\"", "\"40.50\"", "10", null)).andExpect(status().isCreated());

        assertEquals("Ravi", specSentToTheExchange().trader());
    }

    @Test
    void theTradesOfAnOrderAreShownWithRupeePricesAndAPlainStatus() throws Exception {
        Trade trade = new Trade(1, 2, 1, "Anu", "Ravi", 4050, 4, 7);
        when(exchange.placeOrder(any())).thenReturn(new Domain.PlacedOrder(2, 7, 4, 4, 0, 0, List.of(trade)));

        postOrder(order("\"Anu\"", "\"BUY\"", "\"LIMIT\"", "\"41.00\"", "4", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FILLED"))
                .andExpect(jsonPath("$.filledQuantity").value(4))
                .andExpect(jsonPath("$.trades[0].price").value("40.50"))
                .andExpect(jsonPath("$.trades[0].buyer").value("Anu"))
                .andExpect(jsonPath("$.trades[0].seller").value("Ravi"))
                .andExpect(jsonPath("$.trades[0].quantity").value(4));
    }

    @Test
    void aTinyPriceKeepsItsLeadingZeroInTheResponse() throws Exception {
        Trade trade = new Trade(1, 2, 1, "Anu", "Ravi", 5, 1, 7);
        when(exchange.placeOrder(any())).thenReturn(new Domain.PlacedOrder(2, 7, 1, 1, 0, 0, List.of(trade)));

        postOrder(validLimit()).andExpect(jsonPath("$.trades[0].price").value("0.05"));
    }

    // ---------------------------------------------------------------- POST /api/orders: bad requests

    static Stream<Arguments> badRequests() {
        String t = "\"Ravi\"";
        String sell = "\"SELL\"";
        String limit = "\"LIMIT\"";
        String market = "\"MARKET\"";
        String price = "\"40.50\"";
        return Stream.of(
                Arguments.of("missing trader", order(null, sell, limit, price, "10", null), "trader", "trader"),
                Arguments.of("blank trader", order("\"   \"", sell, limit, price, "10", null), "trader", "blank"),
                Arguments.of("too long trader", order("\"" + "x".repeat(65) + "\"", sell, limit, price, "10", null),
                        "trader", "at most 64"),
                Arguments.of("missing side", order(t, null, limit, price, "10", null), "side", "side is required"),
                Arguments.of("unknown side", order(t, "\"SIDEWAYS\"", limit, price, "10", null), null,
                        "field 'side' has the wrong type"),
                Arguments.of("missing type", order(t, sell, null, price, "10", null), "type", "type is required"),
                Arguments.of("unknown type", order(t, sell, "\"STOP\"", price, "10", null), null,
                        "field 'type' has the wrong type"),
                Arguments.of("missing quantity", order(t, sell, limit, price, null, null), "quantity",
                        "quantity is required"),
                Arguments.of("zero quantity", order(t, sell, limit, price, "0", null), "quantity", "greater than zero"),
                Arguments.of("negative quantity", order(t, sell, limit, price, "-5", null), "quantity",
                        "greater than zero"),
                Arguments.of("fractional quantity", order(t, sell, limit, price, "10.5", null), null,
                        "field 'quantity' has the wrong type"),
                Arguments.of("quantity written as 10.0", order(t, sell, limit, price, "10.0", null), null,
                        "field 'quantity' has the wrong type"),
                Arguments.of("quantity as a string", order(t, sell, limit, price, "\"10\"", null), null,
                        "field 'quantity' has the wrong type"),
                Arguments.of("quantity too big", order(t, sell, limit, price, "1000000001", null), "quantity",
                        "must not exceed"),
                Arguments.of("limit without a price", order(t, sell, limit, null, "10", null), "price",
                        "required for a LIMIT"),
                Arguments.of("blank price", order(t, sell, limit, "\"\"", "10", null), "price", "required for a LIMIT"),
                Arguments.of("negative price", order(t, sell, limit, "\"-1\"", "10", null), "price",
                        "plain positive decimal"),
                Arguments.of("price not a number", order(t, sell, limit, "\"abc\"", "10", null), "price",
                        "plain positive decimal"),
                Arguments.of("price with too many decimals", order(t, sell, limit, "\"40.505\"", "10", null),
                        "price", "at most 2 decimal places"),
                Arguments.of("zero price", order(t, sell, limit, "\"0\"", "10", null), "price", "greater than zero"),
                Arguments.of("price in exponent form", order(t, sell, limit, "\"1e2\"", "10", null), "price",
                        "plain positive decimal"),
                Arguments.of("price with a comma", order(t, sell, limit, "\"4,000\"", "10", null), "price",
                        "plain positive decimal"),
                Arguments.of("price as a JSON number", order(t, sell, limit, "40.5", "10", null), null,
                        "field 'price' has the wrong type"),
                Arguments.of("price above the maximum", order(t, sell, limit, "\"100000000.01\"", "10", null),
                        "price", "must not exceed"),
                Arguments.of("market order with a price", order(t, sell, market, price, "10", null), "price",
                        "must not have a price"),
                Arguments.of("market order with GTC", order(t, sell, market, null, "10", "\"GTC\""), "timeInForce",
                        "always IOC"),
                Arguments.of("unknown time in force", order(t, sell, limit, price, "10", "\"FOREVER\""), null,
                        "field 'timeInForce' has the wrong type"),
                Arguments.of("misspelt field", "{\"trader\":\"Ravi\",\"side\":\"SELL\",\"type\":\"LIMIT\","
                        + "\"price\":\"40.50\",\"quantitty\":10}", null, "unknown field 'quantitty'"),
                Arguments.of("malformed JSON", "{", null, "not valid JSON"),
                Arguments.of("empty body", "", null, "missing"),
                Arguments.of("a JSON array instead of an object", "[]", null, "not valid JSON"),
                Arguments.of("a JSON null", "null", null, "missing"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badRequests")
    void aBadRequestIsAnswered400WithAClearMessageAndNeverReachesTheExchange(
            String description, String json, String field, String messageFragment) throws Exception {
        ResultActions result = postOrder(json)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString(messageFragment)));
        if (field != null) {
            result.andExpect(jsonPath("$.details[?(@.field == '" + field + "')]").exists());
        }

        verifyNoInteractions(exchange);
    }

    @Test
    void severalProblemsAreAllReportedTogether() throws Exception {
        postOrder(order("\"\"", null, "\"LIMIT\"", "\"40.50\"", "-1", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.length()").value(3))
                .andExpect(jsonPath("$.details[?(@.field == 'trader')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'side')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'quantity')]").exists());
    }

    // ---------------------------------------------------------------- POST /api/orders: other failures

    @Test
    void aRequestThatIsNotJsonIsAnswered415() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.error").value("Unsupported Media Type"));
    }

    @Test
    void aMethodThatDoesNotExistIsAnswered405InTheSameShape() throws Exception {
        mvc.perform(get("/api/orders"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void anUnknownUrlIsAnswered404InTheSameShape() throws Exception {
        mvc.perform(get("/api/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void ifTheExchangeRefusesTheOrderTheAnswerIs422() throws Exception {
        when(exchange.placeOrder(any())).thenThrow(new OrderRejectedException("duplicate order id 3"));

        postOrder(validLimit())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("duplicate order id 3")));
    }

    @Test
    void ifTheExchangeCannotAnswerTheAnswerIs503() throws Exception {
        when(exchange.placeOrder(any()))
                .thenThrow(new ExchangeUnavailableException("the exchange did not answer within PT5S", null));

        postOrder(validLimit())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value(containsString("did not answer")));
    }

    @Test
    void anUnexpectedErrorIsAnswered500WithoutLeakingItsDetails() throws Exception {
        when(exchange.placeOrder(any())).thenThrow(new IllegalStateException("secret internal detail: db password"));

        postOrder(validLimit())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("an unexpected error occurred"))
                .andExpect(jsonPath("$.message").value(not(containsString("secret"))));
    }

    // ---------------------------------------------------------------- DELETE /api/orders/{id}

    @Test
    void cancellingAnOrderReturnsTheQuantityThatWasCancelled() throws Exception {
        when(exchange.cancelOrder(17)).thenReturn(new Domain.CancelledOrder(17, 9, 6));

        mvc.perform(delete("/api/orders/17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(17))
                .andExpect(jsonPath("$.sequence").value(9))
                .andExpect(jsonPath("$.cancelledQuantity").value(6));
    }

    @Test
    void cancellingAnUnknownOrderIsAnswered404() throws Exception {
        when(exchange.cancelOrder(99)).thenThrow(new OrderNotFoundException(99));

        mvc.perform(delete("/api/orders/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value(containsString("99")));
    }

    @ParameterizedTest(name = "cancel with the id \"{0}\" is a 400")
    @ValueSource(strings = {"abc", "1.5", "99999999999999999999", "0", "-3"})
    void aBadOrderIdInTheUrlIsAnswered400NotFound(String id) throws Exception {
        mvc.perform(delete("/api/orders/" + id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(exchange);
    }

    // ---------------------------------------------------------------- PATCH /api/orders/{id}

    private ResultActions patchOrder(String id, String json) throws Exception {
        return mvc.perform(patch("/api/orders/" + id).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void modifyingAnOrderConvertsThePriceAndReportsWhetherItKeptItsPlace() throws Exception {
        when(exchange.modifyOrder(anyLong(), anyLong(), anyLong()))
                .thenReturn(new Domain.ModifiedOrder(17, 12, true, List.of()));

        patchOrder("17", "{\"price\":\"41.00\",\"quantity\":8}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(17))
                .andExpect(jsonPath("$.sequence").value(12))
                .andExpect(jsonPath("$.keptPriority").value(true))
                .andExpect(jsonPath("$.trades.length()").value(0));

        verify(exchange).modifyOrder(17, 4100, 8);
    }

    @Test
    void aModifyThatTradesShowsItsTradesWithRupeePrices() throws Exception {
        Trade trade = new Trade(3, 17, 4, "Anu", "Ravi", 4200, 5, 12);
        when(exchange.modifyOrder(anyLong(), anyLong(), anyLong()))
                .thenReturn(new Domain.ModifiedOrder(17, 12, false, List.of(trade)));

        patchOrder("17", "{\"price\":\"42.00\",\"quantity\":8}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keptPriority").value(false))
                .andExpect(jsonPath("$.trades[0].price").value("42.00"));
    }

    @Test
    void modifyingAnUnknownOrderIsAnswered404() throws Exception {
        when(exchange.modifyOrder(anyLong(), anyLong(), anyLong())).thenThrow(new OrderNotFoundException(99));

        patchOrder("99", "{\"price\":\"41.00\",\"quantity\":8}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("99")));
    }

    @ParameterizedTest(name = "modify body {0} is a 400")
    @ValueSource(strings = {
            "{\"quantity\":8}",                                   // price missing
            "{\"price\":\"41.00\"}",                              // quantity missing
            "{\"price\":\"41.00\",\"quantity\":0}",
            "{\"price\":\"41.00\",\"quantity\":-2}",
            "{\"price\":\"41.00\",\"quantity\":2.5}",
            "{\"price\":\"41.005\",\"quantity\":8}",
            "{\"price\":\"-41\",\"quantity\":8}",
            "{\"price\":\"0\",\"quantity\":8}",
            "{\"price\":41.0,\"quantity\":8}",                    // price as a JSON number
            "{\"price\":\"41.00\",\"quantity\":8,\"extra\":1}",   // unknown field
            "{}",
            "not json"
    })
    void aBadModifyBodyIsAnswered400AndNeverReachesTheExchange(String body) throws Exception {
        patchOrder("17", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(exchange);
    }

    @ParameterizedTest(name = "modify with the id \"{0}\" is a 400")
    @ValueSource(strings = {"abc", "0", "-1"})
    void aBadOrderIdInAModifyUrlIsAnswered400(String id) throws Exception {
        patchOrder(id, "{\"price\":\"41.00\",\"quantity\":8}").andExpect(status().isBadRequest());

        verifyNoInteractions(exchange);
    }

    @Test
    void theResponsesAreJson() throws Exception {
        when(exchange.cancelOrder(1)).thenReturn(new Domain.CancelledOrder(1, 1, 1));

        mvc.perform(delete("/api/orders/1"))
                .andExpect(header().string("Content-Type", containsString("application/json")));
    }
}
