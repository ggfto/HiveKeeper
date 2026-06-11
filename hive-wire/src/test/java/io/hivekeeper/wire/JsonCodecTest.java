package io.hivekeeper.wire;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Event;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.model.Station;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonCodecTest {

    private final JsonCodec codec = new JsonCodec();

    @Test
    void roundTripsInventoryCommandPolymorphically() {
        Command c = Command.Inventory.of(DeviceRef.ssh("192.168.1.10"));
        String json = codec.toJson(c);
        assertTrue(json.contains("\"@type\""), "expected a type discriminator in: " + json);
        assertEquals(c, codec.fromJson(json, Command.class));
    }

    @Test
    void roundTripsBackupCommand() {
        Command c = new Command.BackupConfig(UUID.randomUUID(), DeviceRef.ssh("10.0.0.2", 2222), true, false);
        assertEquals(c, codec.fromJson(codec.toJson(c), Command.class));
    }

    @Test
    void roundTripsNestedEventAndResult() {
        DeviceId id = DeviceId.of("ap1");
        UUID cid = UUID.randomUUID();
        Device device = new Device(id, "ap230-lab-1", "AP230", "SER123", "10.0r7a", "1 day",
                "192.168.1.10", "hive0", List.of(),
                List.of(new Station("aa:bb:cc:dd:ee:ff", "192.168.1.50", null, "LabWifi", null, -50)));

        Result result = new Result.Inventory(cid, id, device);
        assertEquals(result, codec.fromJson(codec.toJson(result), Result.class));

        // Event.Completed carries a Result -> exercises nested polymorphism.
        Event completed = new Event.Completed(cid, id, result);
        assertEquals(completed, codec.fromJson(codec.toJson(completed), Event.class));

        Event progress = new Event.Progress(cid, id, "working", 42);
        assertEquals(progress, codec.fromJson(codec.toJson(progress), Event.class));
    }
}
