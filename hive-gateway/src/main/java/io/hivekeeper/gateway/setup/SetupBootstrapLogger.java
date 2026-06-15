package io.hivekeeper.gateway.setup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On startup, if the gateway has no organizations yet, print the one-time setup token to the server console so
 * an operator (who has server access) can complete the first-run wizard in the web console. Mirrors the "prints
 * the URL on first run" pattern of self-hosted tools; the token gates {@code POST /api/setup}.
 */
@Slf4j
@Component
@Profile("oidc")
public class SetupBootstrapLogger {

    private final SetupService setup;
    private final String address;
    private final int port;

    public SetupBootstrapLogger(SetupService setup,
                                @Value("${server.address:127.0.0.1}") String address,
                                @Value("${server.port:8090}") int port) {
        this.setup = setup;
        this.address = address;
        this.port = port;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void announceIfUninitialized() {
        if (setup.isInitialized()) {
            return;
        }
        log.warn("""

                ================ HiveKeeper first-run setup ================
                 This gateway has no organizations yet.
                 Open the HiveKeeper web console and append:  ?setup={}
                   setup token : {}
                   gateway     : http://{}:{}
                ============================================================
                """, setup.setupToken(), setup.setupToken(), address, port);
    }
}
