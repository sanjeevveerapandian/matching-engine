package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SubscriptionRegistry;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** Test helper: a real STOMP client over a real WebSocket, the same thing a browser does. */
final class StompTestSupport implements AutoCloseable {

    private final WebSocketStompClient client;
    private final StompSession session;
    private final SubscriptionRegistry serverSubscriptions;

    /**
     * @param serverSubscriptions the server's own record of who is subscribed to what: used to know,
     *                            exactly, when a subscription has taken effect
     */
    StompTestSupport(int port, SubscriptionRegistry serverSubscriptions) throws Exception {
        this.serverSubscriptions = serverSubscriptions;
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        client.setDefaultHeartbeat(new long[] {0, 0});
        session = client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() { })
                .get(10, TimeUnit.SECONDS);
    }

    /** How many subscriptions the SERVER currently holds for this destination. */
    private int subscribersOf(String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(destination);
        Message<String> probe = MessageBuilder.createMessage("", accessor.getMessageHeaders());
        MultiValueMap<String, String> found = serverSubscriptions.findSubscriptions(probe);
        return found.values().stream().mapToInt(java.util.List::size).sum();
    }

    /**
     * Subscribe and WAIT until the server's registry shows the new subscription, so that nothing
     * published afterwards can be missed. Everything received lands in the returned queue.
     */
    @SuppressWarnings("unchecked")
    <T> BlockingQueue<T> subscribe(String destination, Class<T> payloadType) throws Exception {
        int before = subscribersOf(destination);
        BlockingQueue<T> received = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return payloadType;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((T) payload);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (subscribersOf(destination) <= before) {
            if (System.nanoTime() > deadline) {
                fail("the server never registered the subscription to " + destination);
            }
            Thread.sleep(5);
        }
        return received;
    }

    /** Try to publish to a topic, as a hostile client would. */
    void sendTo(String destination, Object payload) {
        session.send(destination, payload);
    }

    @Override
    public void close() {
        if (session.isConnected()) {
            session.disconnect();
        }
        client.stop();
    }
}
