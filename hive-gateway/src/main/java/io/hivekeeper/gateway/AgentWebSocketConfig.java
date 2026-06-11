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
    private final AgentAuthInterceptor authInterceptor;

    AgentWebSocketConfig(AgentWebSocketHandler handler, AgentAuthInterceptor authInterceptor) {
        this.handler = handler;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/agent")
                .addInterceptors(authInterceptor)
                .setAllowedOrigins("*");
    }
}
