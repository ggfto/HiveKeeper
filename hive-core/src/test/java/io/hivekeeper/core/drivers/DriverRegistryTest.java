package io.hivekeeper.core.drivers;

import io.hivekeeper.core.model.ConfigSnapshot;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.HiveSpec;
import io.hivekeeper.core.model.SsidSpec;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverRegistryTest {

    @Test
    void identifyReturnsFirstDriverThatRecognizes() throws Exception {
        DriverRegistry registry = new DriverRegistry(List.of(stub("no", false), stub("yes", true)));
        assertEquals("yes", registry.identify(cmd -> "").orElseThrow().id());
    }

    @Test
    void identifyEmptyWhenNoneRecognize() throws Exception {
        DriverRegistry registry = new DriverRegistry(List.of(stub("no", false)));
        assertTrue(registry.identify(cmd -> "").isEmpty());
    }

    @Test
    void serviceLoaderDiscoversHiveOsDriver() {
        assertTrue(DriverRegistry.fromServiceLoader().all().stream()
                .anyMatch(d -> d.id().equals("hiveos")));
    }

    private static Driver stub(String id, boolean recognizes) {
        return new Driver() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean recognizes(CliExecutor exec) {
                return recognizes;
            }

            @Override
            public Device inventory(DeviceId deviceId, CliExecutor exec, ProgressReporter progress) {
                return null;
            }

            @Override
            public ConfigSnapshot captureConfig(DeviceId deviceId, CliExecutor exec, ConfigScope scope,
                                                ProgressReporter progress) {
                return null;
            }

            @Override
            public List<String> applyConfig(DeviceId deviceId, CliExecutor exec, List<String> commands,
                                            boolean save, ProgressReporter progress) {
                return List.of();
            }

            @Override
            public List<String> ssidCommands(SsidSpec spec) {
                return List.of();
            }

            @Override
            public List<String> hiveCommands(HiveSpec spec) {
                return List.of();
            }
        };
    }
}
