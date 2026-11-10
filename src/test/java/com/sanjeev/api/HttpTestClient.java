package com.sanjeev.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Test helper: plain HTTP over a real socket (the JDK client supports PATCH; Spring's default one does not). */
final class HttpTestClient {

    /** What came back: the status code and the parsed JSON body (null if there was none). */
    record Reply(int status, JsonNode body) { }

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String base;

    HttpTestClient(int port) {
        this.base = "http://localhost:" + port;
    }

    Reply send(String method, String path, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        HttpRequest request = (json == null
                ? builder.method(method, HttpRequest.BodyPublishers.noBody())
                : builder.method(method, HttpRequest.BodyPublishers.ofString(json))).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String text = response.body();
        return new Reply(response.statusCode(), text == null || text.isBlank() ? null : mapper.readTree(text));
    }

    Reply get(String path) throws Exception {
        return send("GET", path, null);
    }

    Reply post(String path, String json) throws Exception {
        return send("POST", path, json);
    }

    /** A limit-order request body. */
    static String limitOrder(String trader, String side, String price, long quantity) {
        return "{\"trader\":\"" + trader + "\",\"side\":\"" + side + "\",\"type\":\"LIMIT\",\"price\":\""
                + price + "\",\"quantity\":" + quantity + "}";
    }
}
