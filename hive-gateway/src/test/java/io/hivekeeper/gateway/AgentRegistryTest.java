package io.hivekeeper.gateway;

import io.hivekeeper.protocol.Frame;
import io.hivekeeper.protocol.FrameChannel;
import io.hivekeeper.protocol.RemoteEngine;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.function.Consumer;
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
    void registersAndLooksUpScopedByTenant() {
        AgentRegistry registry = new AgentRegistry();
        RemoteEngine engine = stubEngine();

        registry.register("acme", "lab-agent", "session-1", engine);

        assertSame(engine, registry.engine("acme", "lab-agent").orElseThrow());
        assertTrue(registry.agentIds("acme").contains("lab-agent"));
    }

    @Test
    void doesNotLeakAcrossTenants() {
        AgentRegistry registry = new AgentRegistry();
        registry.register("acme", "lab-agent", "session-1", stubEngine());

        assertTrue(registry.engine("globex", "lab-agent").isEmpty(), "another tenant must not resolve the agent");
        assertTrue(registry.agentIds("globex").isEmpty());
    }

    @Test
    void unregisterBySessionRemovesTheAgent() {
        AgentRegistry registry = new AgentRegistry();
        registry.register("acme", "lab-agent", "session-1", stubEngine());

        registry.unregisterBySession("session-1");

        assertTrue(registry.engine("acme", "lab-agent").isEmpty());
        assertTrue(registry.agentIds("acme").isEmpty());
    }
}
