package io.hivekeeper.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The cloud control plane (north-star mode C), as a vertical slice: a WebSocket server that accepts
 * outbound agent connections and a REST API that dispatches work to a chosen agent through a
 * {@link io.hivekeeper.protocol.RemoteEngine}. The gateway never connects into a LAN and never holds
 * device credentials — it only routes intent. Job-DB redelivery, mTLS enrollment, and the multi-tenant
 * data model are the next layers on top of this.
 */
@SpringBootApplication
public class HiveGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(HiveGatewayApplication.class, args);
    }
}
