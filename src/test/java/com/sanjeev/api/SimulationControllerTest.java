package com.sanjeev.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SimulationController.class)
class SimulationControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TrafficSimulator simulator;

    private static final Domain.SimulationResult RESULT = new Domain.SimulationResult(1000, 551, 99, 224, 100, 12, 0, 312);

    @Test
    void theDefaultIsAThousandOperationsAndTheResponseShowsWhatHappened() throws Exception {
        when(simulator.run(eq(1000), anyLong())).thenReturn(RESULT);

        mvc.perform(post("/api/simulate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1000))
                .andExpect(jsonPath("$.limitOrders").value(551))
                .andExpect(jsonPath("$.marketOrders").value(99))
                .andExpect(jsonPath("$.cancels").value(224))
                .andExpect(jsonPath("$.modifies").value(100))
                .andExpect(jsonPath("$.notFound").value(12))
                .andExpect(jsonPath("$.trades").value(312))
                .andExpect(jsonPath("$.seed").exists());
    }

    @Test
    void theCountAndSeedAreHandedToTheSimulator() throws Exception {
        when(simulator.run(500, 42)).thenReturn(RESULT);

        mvc.perform(post("/api/simulate").param("count", "500").param("seed", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").value(42));

        verify(simulator).run(500, 42);
    }

    @Test
    void withoutASeedEachRunGetsItsOwnAndReportsIt() throws Exception {
        when(simulator.run(anyInt(), anyLong())).thenReturn(RESULT);

        mvc.perform(post("/api/simulate").param("count", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seed").isNumber());
    }

    @ParameterizedTest(name = "count \"{0}\" is accepted")
    @ValueSource(strings = {"1", "10000"})
    void theCountLimitsThemselvesAreAccepted(String count) throws Exception {
        when(simulator.run(anyInt(), anyLong())).thenReturn(RESULT);

        mvc.perform(post("/api/simulate").param("count", count)).andExpect(status().isOk());
    }

    @ParameterizedTest(name = "count \"{0}\" is a 400")
    @ValueSource(strings = {"0", "-5", "10001", "abc", "2.5", "99999999999999999999"})
    void aBadCountIsAnswered400AndNothingIsSimulated(String count) throws Exception {
        mvc.perform(post("/api/simulate").param("count", count))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(simulator);
    }

    @Test
    void anOutOfRangeCountSaysWhatTheRangeIs() throws Exception {
        mvc.perform(post("/api/simulate").param("count", "50000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("between 1 and 10000")))
                .andExpect(jsonPath("$.details[0].field").value("count"));
    }

    @Test
    void aBadSeedIsAnswered400() throws Exception {
        mvc.perform(post("/api/simulate").param("seed", "abc"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(simulator);
    }

    @Test
    void simulatingIsAPostNotAGet() throws Exception {
        mvc.perform(get("/api/simulate")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void ifTheExchangeStopsTheAnswerIs503() throws Exception {
        when(simulator.run(anyInt(), anyLong()))
                .thenThrow(new ExchangeUnavailableException("the exchange is not accepting requests", null));

        mvc.perform(post("/api/simulate")).andExpect(status().isServiceUnavailable());
    }
}
