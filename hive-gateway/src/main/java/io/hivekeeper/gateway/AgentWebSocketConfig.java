package io.hivekeeper.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/** Exposes the agent uplink at {@code /agent}. */
@Configuration
@EnableWebSocket
class AgentWebSocketConfig implements WebSocketConfigurer {

    // A single agent result can be large — a full `show log buffered` is ~0.5 MB of JSON, and a full
    // running-config backup can be bigger. Spring's default text buffer is 8 KB, so without this the gateway
    // closes the socket (1009 TOO_BIG) the moment such a result arrives and the RPC times out. 8 MB gives ample
    // headroom for any single CLI capture while still bounding memory per message.
    private static final int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;

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

    /** Raise the container's WebSocket message buffers so large agent results (full logs/configs) are not
     *  truncated/closed at the 8 KB default. */
    @Bean
    ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_MESSAGE_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_MESSAGE_BYTES);
        return container;
    }
}
