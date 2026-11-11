package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The health endpoint on a real running server, and the proof that nothing else of the actuator is public. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorHealthIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("matching.data-dir", () -> dataDir.toString());
    }

    @LocalServerPort
    int port;

    @Test
    void healthSaysUpWhenTheExchangeIsRunning() throws Exception {
        HttpTestClient.Reply reply = new HttpTestClient(port).get("/actuator/health");

        assertEquals(200, reply.status());
        assertEquals("UP", reply.body().get("status").asText());
    }

    @Test
    void healthRevealsNothingButTheStatus() throws Exception {
        JsonNode body = new HttpTestClient(port).get("/actuator/health").body();

        assertEquals(1, body.size(), "the health answer must contain only the status, got " + body);
    }

    @Test
    void everyOtherActuatorEndpointIsNotPublic() throws Exception {
        HttpTestClient http = new HttpTestClient(port);

        for (String endpoint : List.of("env", "beans", "metrics", "info", "mappings", "configprops",
                "threaddump", "heapdump", "loggers", "shutdown", "health/exchange")) {
            int status = http.get("/actuator/" + endpoint).status();
            assertTrue(status == 404, "/actuator/" + endpoint + " answered " + status + " but must not be public");
        }
    }

    @Test
    void healthDoesNotNeedTheRestApiToBeUsedFirst() throws Exception {
        // a host probes this the moment the process starts, before any trader has done anything
        assertEquals(200, new HttpTestClient(port).get("/actuator/health").status());
    }
}
