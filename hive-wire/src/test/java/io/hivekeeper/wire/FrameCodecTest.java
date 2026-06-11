package io.hivekeeper.wire;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Event;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.protocol.Frame;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameCodecTest {

    private final JsonCodec codec = new JsonCodec();

    @Test
    void roundTripsJobFrameCarryingACommand() {
        Frame frame = new Frame.Job("job-1", "idem-1", 1_700_000_000_000L,
                Command.Inventory.of(DeviceRef.ssh("10.0.0.1")));
        String json = codec.toJson(frame);
        assertTrue(json.contains("\"@type\""), "expected type discriminators in: " + json);
        assertEquals(frame, codec.fromJson(json, Frame.class));
    }

    @Test
    void roundTripsJobEventFrame() {
        DeviceId id = DeviceId.of("ap");
        Frame frame = new Frame.JobEvent("job-1", 3,
                new Event.Progress(UUID.randomUUID(), id, "working", 50));
        assertEquals(frame, codec.fromJson(codec.toJson(frame), Frame.class));
    }

    @Test
    void roundTripsJobResultFrameWithNestedDevice() {
        DeviceId id = DeviceId.of("ap");
        Device device = new Device(id, null, "AP230", "SER", "10.6r1a", null, "192.168.1.101", "hive0",
                List.of(), List.of());
        Frame frame = new Frame.JobResult("job-1", new Result.Inventory(UUID.randomUUID(), id, device));
        assertEquals(frame, codec.fromJson(codec.toJson(frame), Frame.class));
    }

    @Test
    void roundTripsConnectionManagementFrames() {
        assertEquals(new Frame.Hello("agent-1", "1.0"),
                codec.fromJson(codec.toJson(new Frame.Hello("agent-1", "1.0")), Frame.class));
        assertEquals(new Frame.Heartbeat(123L),
                codec.fromJson(codec.toJson(new Frame.Heartbeat(123L)), Frame.class));
    }
}
