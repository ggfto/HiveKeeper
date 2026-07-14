package io.hivekeeper.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what "healthy" means for the agent: connected to its gateway, not merely running. An agent whose uplink
 * is down can do nothing, and a check that cannot tell those apart reports green while the fleet is
 * unmanageable.
 */
class AgentHealthTest {

    /** Builds an AgentHealth around an arbitrary connectivity signal, bypassing the WebSocket channel. */
    private static AgentHealth healthFor(Path file, BooleanSupplier connected) throws Exception {
        Constructor<AgentHealth> ctor = AgentHealth.class.getDeclaredConstructor(Path.class, BooleanSupplier.class);
        ctor.setAccessible(true);
        return ctor.newInstance(file, connected);
    }

    @Test
    void writesTheHeartbeatWhileConnected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("agent.health");
        AgentHealth health = healthFor(file, () -> true);

        health.report();

        assertTrue(Files.exists(file));
        assertFalse(Files.readString(file).isBlank());
    }

    @Test
    void removesTheHeartbeatWhenTheUplinkDrops(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("agent.health");
        AtomicBoolean connected = new AtomicBoolean(true);
        AgentHealth health = healthFor(file, connected::get);

        health.report();
        assertTrue(Files.exists(file), "connected: the heartbeat should be there");

        // The process is still very much alive here — only the gateway uplink went away. That must read as
        // unhealthy, which is the entire point.
        connected.set(false);
        health.report();

        assertFalse(Files.exists(file), "disconnected: the heartbeat must be gone");
    }

    @Test
    void heartbeatsAgainOnceTheUplinkComesBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("agent.health");
        AtomicBoolean connected = new AtomicBoolean(false);
        AgentHealth health = healthFor(file, connected::get);

        health.report();
        assertFalse(Files.exists(file));

        connected.set(true);
        health.report();

        assertTrue(Files.exists(file));
    }

    @Test
    void anUnwritableHealthFileNeverKillsTheAgent(@TempDir Path dir) throws Exception {
        // The file lives under a path that is not a directory, so every write fails. A full disk (or a bad path)
        // must not take a working agent offline — the resulting stale file IS the signal.
        Path notADirectory = dir.resolve("regular-file");
        Files.writeString(notADirectory, "x");
        AgentHealth health = healthFor(notADirectory.resolve("agent.health"), () -> true);

        health.report(); // must not throw
    }

    @Test
    void closingRemovesTheHeartbeat(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("agent.health");
        AgentHealth health = healthFor(file, () -> true);
        health.report();

        health.close();

        assertFalse(Files.exists(file), "a stopped agent must not leave a fresh heartbeat behind");
    }

    @Test
    void theStalenessWindowIsSeveralHeartbeats() throws IOException {
        // A supervisor calls the agent unhealthy once the file goes stale, so the window has to absorb a slow
        // write without flapping — and still fail fast enough to be useful.
        assertTrue(AgentHealth.STALE_AFTER.compareTo(AgentHealth.INTERVAL.multipliedBy(3)) >= 0,
                "the staleness window should span at least three heartbeats");
    }
}
