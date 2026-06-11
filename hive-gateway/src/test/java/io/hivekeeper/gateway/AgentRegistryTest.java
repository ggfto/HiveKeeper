package io.hivekeeper.gateway;

import io.hivekeeper.protocol.Frame;
import io.hivekeeper.protocol.FrameChannel;
import io.hivekeeper.protocol.RemoteEngine;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRegistryTest {

    private static RemoteEngine stubEngine() {
        FrameChannel noop = new FrameChannel() {
            @Override public void send(Frame frame) { }
            @Override public void onFrame(Consumer<Frame> handler) { }
            @Override public void close() { }
        };
        return new RemoteEngine(noop, Duration.ofSeconds(1));
    }

    @Test
    void registersAndLooksUpByAgentId() {
        AgentRegistry registry = new AgentRegistry();
        RemoteEngine engine = stubEngine();

        registry.register("agent-1", "session-1", engine);

        assertTrue(registry.agentIds().contains("agent-1"));
        assertSame(engine, registry.engine("agent-1").orElseThrow());
    }

    @Test
    void unregisterBySessionRemovesTheAgent() {
        AgentRegistry registry = new AgentRegistry();
        registry.register("agent-1", "session-1", stubEngine());

        registry.unregisterBySession("session-1");

        assertTrue(registry.engine("agent-1").isEmpty());
        assertEquals(0, registry.agentIds().size());
    }

    @Test
    void unknownAgentResolvesEmpty() {
        assertTrue(new AgentRegistry().engine("nope").isEmpty());
    }
}
