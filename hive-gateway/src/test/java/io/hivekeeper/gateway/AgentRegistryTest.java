package io.hivekeeper.gateway;

import io.hivekeeper.protocol.Frame;
import io.hivekeeper.protocol.FrameChannel;
import io.hivekeeper.protocol.RemoteEngine;
import org.junit.jupiter.api.Test;
import java.security.PublicKey;
import java.time.Duration;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        assertTrue(registry.unregisterBySession("session-1"), "the live session going down IS a disconnect");

        assertTrue(registry.engine("acme", "lab-agent").isEmpty());
        assertTrue(registry.agentIds("acme").isEmpty());
    }

    @Test
    void aLateCloseFromASupersededSessionDoesNotEvictTheReconnectedAgent() {
        // The abrupt-drop sequence: the agent's socket dies without a close frame (1006), it reconnects within
        // a second on a new session, and only THEN does the gateway notice the old half-open socket and close
        // it. That straggler must not take the live connection with it.
        AgentRegistry registry = new AgentRegistry();
        registry.register("acme", "lab-agent", "session-1", stubEngine());
        RemoteEngine reconnected = stubEngine();
        registry.register("acme", "lab-agent", "session-2", reconnected);

        assertFalse(registry.unregisterBySession("session-1"), "a superseded close is not a disconnect");

        assertSame(reconnected, registry.engine("acme", "lab-agent").orElseThrow(),
                "the agent must still be connected on its newer session");
        assertTrue(registry.agentIds("acme").contains("lab-agent"), "and still be listed as online");
    }

    @Test
    void theReconnectedSessionCanStillBeUnregistered() {
        // The straggler above must not leave the new session unable to clean itself up later.
        AgentRegistry registry = new AgentRegistry();
        registry.register("acme", "lab-agent", "session-1", stubEngine());
        registry.register("acme", "lab-agent", "session-2", stubEngine());
        registry.unregisterBySession("session-1");

        assertTrue(registry.unregisterBySession("session-2"));

        assertTrue(registry.engine("acme", "lab-agent").isEmpty());
    }

    @Test
    void unregisteringAnUnknownSessionIsNotADisconnect() {
        AgentRegistry registry = new AgentRegistry();
        assertFalse(registry.unregisterBySession("never-seen"));
    }

    @Test
    void aLateCloseDoesNotStripTheReconnectedAgentsPublicKey() {
        // The key is what job secrets get sealed to; losing it silently downgrades sealing to the insecure
        // dev fallback rather than failing loudly, so it has to survive the same race.
        AgentRegistry registry = new AgentRegistry();
        PublicKey key = stubPublicKey();
        registry.register("acme", "lab-agent", "session-1", stubEngine());
        registry.register("acme", "lab-agent", "session-2", stubEngine());
        registry.registerPublicKey("acme", "lab-agent", key);

        registry.unregisterBySession("session-1");

        assertSame(key, registry.publicKey("acme", "lab-agent").orElseThrow());
    }

    private static PublicKey stubPublicKey() {
        try {
            return java.security.KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
