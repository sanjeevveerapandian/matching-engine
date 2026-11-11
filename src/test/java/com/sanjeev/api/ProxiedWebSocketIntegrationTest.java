package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Behind a hosting platform the browser talks HTTPS to the platform's proxy, which forwards plain HTTP to us
 * and tells us the public host name in X-Forwarded-* headers. The WebSocket must still accept the page's own
 * origin, and must still refuse any other website.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProxiedWebSocketIntegrationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("matching.data-dir", () -> dataDir.toString());
    }

    @LocalServerPort
    int port;

    private static final WebSocket.Listener QUIET = new WebSocket.Listener() { };

    /** Try the handshake; returns the HTTP status it ended with (101 = accepted). */
    private int handshake(String origin, String forwardedHost, String forwardedProto) throws Exception {
        WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder().subprotocols("v12.stomp");
        if (origin != null) {
            builder.header("Origin", origin);
        }
        if (forwardedHost != null) {
            builder.header("X-Forwarded-Host", forwardedHost);
        }
        if (forwardedProto != null) {
            builder.header("X-Forwarded-Proto", forwardedProto);
        }
        try {
            WebSocket socket = builder.buildAsync(URI.create("ws://localhost:" + port + "/ws"), QUIET)
                    .get(10, TimeUnit.SECONDS);
            socket.abort();
            return 101;
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof WebSocketHandshakeException refused) {
                return refused.getResponse().statusCode();
            }
            throw e;
        } catch (CompletionException e) {
            throw e;
        }
    }

    @Test
    void aClientWithNoOriginAtAllIsAccepted() throws Exception {
        assertEquals(101, handshake(null, null, null)); // not a browser: scripts, tests, curl
    }

    @Test
    void thePageServedFromTheSameHostIsAccepted() throws Exception {
        assertEquals(101, handshake("http://localhost:" + port, null, null));
    }

    @Test
    void anotherWebsiteIsRefused() throws Exception {
        assertEquals(403, handshake("https://evil.example.com", null, null));
    }

    @Test
    void thePublicHttpsPageBehindAProxyIsAcceptedBecauseTheProxyTellsUsTheHostName() throws Exception {
        assertEquals(101, handshake("https://matching-engine.onrender.com", "matching-engine.onrender.com", "https"));
    }

    @Test
    void anotherWebsiteIsStillRefusedBehindTheProxy() throws Exception {
        int status = handshake("https://evil.example.com", "matching-engine.onrender.com", "https");

        assertTrue(status == 403, "a foreign origin must stay refused behind the proxy, got " + status);
    }
}
