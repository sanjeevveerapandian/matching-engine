package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/** The feed is read-only: clients may subscribe, and nothing else. */
class WebSocketConfigTest {

    private final WebSocketConfig.ReadOnlyFeed interceptor = new WebSocketConfig.ReadOnlyFeed();

    private static Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage("{}".getBytes(), accessor.getMessageHeaders());
    }

    @Test
    void aClientTryingToPublishToTheTradesTopicIsRefused() {
        assertNull(interceptor.preSend(frame(StompCommand.SEND, "/topic/trades"), null));
    }

    @Test
    void aClientTryingToPublishToTheBookTopicIsRefused() {
        assertNull(interceptor.preSend(frame(StompCommand.SEND, "/topic/book"), null));
    }

    @Test
    void aSendToAnyOtherDestinationIsRefusedToo() {
        assertNull(interceptor.preSend(frame(StompCommand.SEND, "/app/anything"), null));
        assertNull(interceptor.preSend(frame(StompCommand.SEND, "/queue/x"), null));
    }

    @Test
    void connectingAndSubscribingAreAllowed() {
        Message<byte[]> connect = frame(StompCommand.CONNECT, null);
        Message<byte[]> subscribe = frame(StompCommand.SUBSCRIBE, "/topic/trades");

        assertSame(connect, interceptor.preSend(connect, null));
        assertSame(subscribe, interceptor.preSend(subscribe, null));
    }

    @Test
    void unsubscribingAndDisconnectingAreAllowed() {
        assertNotNull(interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, null), null));
        assertNotNull(interceptor.preSend(frame(StompCommand.DISCONNECT, null), null));
    }

    @Test
    void aMessageThatIsNotStompAtAllPassesThroughUntouched() {
        Message<String> plain = MessageBuilder.withPayload("hello").build();

        assertSame(plain, interceptor.preSend(plain, null));
    }
}
