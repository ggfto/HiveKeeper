package io.hivekeeper.gateway;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Exposes the agent uplink at {@code /agent}. */
@Configuration
@EnableWebSocket
class AgentWebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler handler;

    AgentWebSocketConfig(AgentWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/agent").setAllowedOrigins("*");
    }
}
