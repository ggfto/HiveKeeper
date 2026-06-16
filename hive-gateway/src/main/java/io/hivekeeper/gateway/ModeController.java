package io.hivekeeper.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the web app which deployment shape it is talking to, so a single SPA build adapts at runtime. Right now
 * that is just {@code solo}: a single-user, single-AP local mode (no organizations, no sign-in) toggled by the
 * {@code HIVEKEEPER_SOLO} env var. Unauthenticated on purpose — the SPA reads it before it has any identity, and
 * it reveals nothing sensitive (only whether solo mode is on). Always present, in every profile.
 */
@RestController
public class ModeController {

    private static final Logger log = LoggerFactory.getLogger(ModeController.class);

    public record ModeResponse(boolean solo) {
    }

    private final boolean solo;

    public ModeController(@Value("${hivekeeper.solo:false}") boolean solo) {
        this.solo = solo;
        if (solo) {
            log.warn("SOLO MODE is ON: authentication is disabled and every request is the local owner. "
                    + "Run this only on a trusted machine and bind the gateway to localhost.");
        }
    }

    @GetMapping("/api/mode")
    public ModeResponse mode() {
        return new ModeResponse(solo);
    }
}
