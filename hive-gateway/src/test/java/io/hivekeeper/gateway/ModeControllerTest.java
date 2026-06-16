package io.hivekeeper.gateway;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModeControllerTest {

    @Test
    void reportsTheConfiguredSoloFlag() {
        assertFalse(new ModeController(false).mode().solo(), "default deployment is not solo");
        assertTrue(new ModeController(true).mode().solo(), "HIVEKEEPER_SOLO=true flips it on");
    }
}
