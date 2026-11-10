package com.sanjeev.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * The live feed over STOMP on a WebSocket.
 *
 * <ul>
 *   <li>Connect to {@code ws://host:port/ws}.</li>
 *   <li>Subscribe to {@code /topic/book} and {@code /topic/trades}.</li>
 *   <li>The feed is READ-ONLY: clients may subscribe, and nothing else. Spring's simple broker
 *       would otherwise relay anything a client SENDs to a topic to every other subscriber, which
 *       would let anyone broadcast a forged trade to all viewers.</li>
 * </ul>
 *
 * <p>Browsers may only connect from the same origin as the page (Spring's default), which stops
 * other websites from opening the feed on a visitor's behalf.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketConfig.class);

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        // Delivery to clients runs on a thread pool, so two messages published in order could reach
        // a viewer swapped. A trade tape in the wrong order is wrong, so keep publish order.
        registry.setPreservePublishOrder(true);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ReadOnlyFeed());
    }

    /** Drops every STOMP SEND from a client. Subscribing and connecting are unaffected. */
    static final class ReadOnlyFeed implements ChannelInterceptor {

        @Override
        public Message<?> preSend(Message<?> message, MessageChannel channel) {
            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
            if (accessor.getCommand() == StompCommand.SEND) {
                LOG.warn("a client tried to publish to {}: refused (the feed is read-only)", accessor.getDestination());
                return null; // null means: do not deliver this message
            }
            return message;
        }
    }
}
