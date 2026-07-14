package io.hivekeeper.agent;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Reports the agent's health as a file whose freshness is the signal: while the uplink to the gateway is open
 * the agent touches it every {@link #INTERVAL}; when the uplink drops, the agent stops (and removes it). A
 * supervisor — Docker's {@code HEALTHCHECK}, systemd, a monitoring script — calls the agent unhealthy once the
 * file is missing or older than {@link #STALE_AFTER}.
 *
 * <p>A file, rather than the obvious HTTP health endpoint, because the agent's defining property is that it
 * <b>never opens an inbound port</b>: it dials out to the gateway, so nothing on the LAN can reach it. Adding a
 * health port — even bound to localhost — would trade that away for a convenience, and it is the one property
 * that lets an operator run this next to their APs without thinking about firewalls.
 *
 * <p>"Healthy" deliberately means <i>connected to the gateway</i>, not <i>process is alive</i>. An agent whose
 * uplink is down can do nothing at all, and a liveness check that cannot tell those two apart reports green
 * while the fleet is unmanageable.
 */
@Slf4j
final class AgentHealth implements AutoCloseable {

    /** How often the heartbeat is written while connected. */
    static final Duration INTERVAL = Duration.ofSeconds(15);

    /** A heartbeat older than this means unhealthy. Several intervals, so one slow write is not an outage. */
    static final Duration STALE_AFTER = Duration.ofSeconds(60);

    private final Path file;
    private final BooleanSupplier connected;
    private final ScheduledExecutorService scheduler;

    private AgentHealth(Path file, BooleanSupplier connected) {
        this.file = file;
        this.connected = connected;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-health");
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts reporting health for {@code channel} into {@code file}. */
    static AgentHealth start(Path file, WebSocketFrameChannel channel) {
        AgentHealth health = new AgentHealth(file, channel::isConnected);
        health.scheduler.scheduleAtFixedRate(
                health::report, 0, INTERVAL.toSeconds(), TimeUnit.SECONDS);
        log.info("health file: {} (heartbeat every {}s while connected)", file, INTERVAL.toSeconds());
        return health;
    }

    /** One heartbeat: refresh the file while connected, remove it while not. Visible for testing. */
    void report() {
        try {
            if (connected.getAsBoolean()) {
                Files.writeString(file, Instant.now().toString());
            } else {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            // Never let a health write kill the agent — a full disk must not take the fleet offline. The stale
            // file that results is itself the correct signal.
            log.warn("could not update the health file {}: {}", file, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("health report failed: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("could not remove the health file {}: {}", file, e.getMessage());
        }
    }
}
