package io.hivekeeper.server;

import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.spi.Credentials;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerSupportTest {

    @Test
    void deviceRefAppliesDefaultPort() {
        DeviceRef ref = ServerSupport.deviceRef(new ConnectionRequest("192.168.1.101", null, null, null, null));
        assertEquals("192.168.1.101", ref.host());
        assertEquals(22, ref.port());
    }

    @Test
    void deviceRefHonoursExplicitPort() {
        DeviceRef ref = ServerSupport.deviceRef(new ConnectionRequest("ap", 2222, null, null, null));
        assertEquals(2222, ref.port());
    }

    @Test
    void deviceRefRejectsMissingHost() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerSupport.deviceRef(new ConnectionRequest("  ", null, null, null, null)));
    }

    @Test
    void credentialsDefaultUserToAdmin() {
        Credentials creds = ServerSupport.credentials(new ConnectionRequest("ap", null, null, "pw", null))
                .resolve(DeviceRef.ssh("ap")).orElseThrow();
        assertEquals("admin", creds.username());
        assertEquals("pw", creds.password());
    }

    @Test
    void backupDirDefaultsWhenBlank() {
        assertEquals(ServerSupport.DEFAULT_DIR,
                ServerSupport.backupDir(new ConnectionRequest("ap", null, null, null, "  ")));
        assertEquals("custom",
                ServerSupport.backupDir(new ConnectionRequest("ap", null, null, null, "custom")));
    }
}
