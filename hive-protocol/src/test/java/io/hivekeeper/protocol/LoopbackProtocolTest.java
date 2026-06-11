package io.hivekeeper.protocol;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.Event;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceRef;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves "remote == local is just wiring": a RemoteEngine drives an agent-side Engine over an
 *  in-memory channel, with events streaming back and the terminal result returned — no socket. */
class LoopbackProtocolTest {

    @Test
    void remoteEngineRoundTripsThroughAgentEngine() {
        Engine agentEngine = (command, sink) -> {
            var id = command.commandId();
            var deviceId = ((Command.Inventory) command).device().id();
            sink.emit(new Event.Started(id, deviceId, "stub"));
            sink.emit(new Event.Progress(id, deviceId, "working", 50));
            Device device = Device.builder().id(deviceId).model("AP230").build();
            Result result = new Result.Inventory(id, deviceId, device);
            sink.emit(new Event.Completed(id, deviceId, result));
            return result;
        };

        try (InMemoryChannelPair pair = new InMemoryChannelPair();
             AgentRuntime agent = new AgentRuntime(agentEngine, pair.agentSide(), "agent-1")) {
            agent.start();
            RemoteEngine remote = new RemoteEngine(pair.gatewaySide(), Duration.ofSeconds(5));

            List<Event> streamed = new ArrayList<>();
            Result result = remote.execute(Command.Inventory.of(DeviceRef.ssh("192.168.1.101")), streamed::add);

            Result.Inventory inv = assertInstanceOf(Result.Inventory.class, result);
            assertEquals("AP230", inv.device().model());
            assertTrue(streamed.stream().anyMatch(e -> e instanceof Event.Progress),
                    "progress events should stream back to the gateway's sink");
        }
    }

    @Test
    void remoteEnginePropagatesAgentFailure() {
        Engine failing = (command, sink) -> {
            throw new RuntimeException("boom");
        };

        try (InMemoryChannelPair pair = new InMemoryChannelPair();
             AgentRuntime agent = new AgentRuntime(failing, pair.agentSide(), "agent-1")) {
            agent.start();
            RemoteEngine remote = new RemoteEngine(pair.gatewaySide(), Duration.ofSeconds(5));

            assertThrows(HiveException.class,
                    () -> remote.execute(Command.Inventory.of(DeviceRef.ssh("1.2.3.4")), event -> { }));
        }
    }
}
