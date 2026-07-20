package io.hivekeeper.gateway;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.crypto.EnvelopeCipher;
import io.hivekeeper.gateway.backup.BackupDestinationService;
import io.hivekeeper.protocol.RemoteEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Delivers an organization's backup destination to its agents, sealed to each agent's own key.
 *
 * <p>This is the piece that makes a per-organization setting actually hold. Configuring it reaches the
 * agents connected at that moment; this also runs when an agent connects, so a site that was offline when
 * the destination was set — or enrolled afterwards — picks it up on its own. Without that, "configured for
 * the organization" would quietly mean "configured for whoever happened to be online".
 */
@Slf4j
@Component
public class BackupDestinationProvisioner {

    private final BackupDestinationService destinations;
    private final AgentRegistry registry;
    private final EnvelopeCipher envelope = new EnvelopeCipher();
    // Agents are provisioned off the WebSocket thread: RemoteEngine blocks waiting for the agent's reply, and
    // that reply arrives on the very thread that would be waiting.
    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "gw-backup-dest");
        thread.setDaemon(true);
        return thread;
    });

    public BackupDestinationProvisioner(BackupDestinationService destinations, AgentRegistry registry) {
        this.destinations = destinations;
        this.registry = registry;
    }

    /** Whether one agent took the destination. */
    public record Delivery(String agentId, boolean delivered, String error) {
    }

    /**
     * Pushes the tenant's currently stored destination to every connected agent, or clears it everywhere
     * when none is stored. Never throws: one unreachable agent must not stop the others.
     */
    public List<Delivery> deliverToAll(String tenantId) {
        List<Delivery> out = new ArrayList<>();
        for (String agentId : registry.agentIds(tenantId)) {
            out.add(deliver(tenantId, agentId));
        }
        return out;
    }

    /** Pushes to one agent, reporting rather than throwing. */
    public Delivery deliver(String tenantId, String agentId) {
        try {
            BackupDestinationService.Destination dest = destinations.get(tenantId).orElse(null);
            RemoteEngine agent = registry.engine(tenantId, agentId)
                    .orElseThrow(() -> new IllegalStateException("agent is not connected"));

            Command command;
            if (dest == null) {
                command = Command.ConfigureBackupDestination.of(null, null, null, null);
            } else {
                PublicKey agentKey = registry.publicKey(tenantId, agentId).orElse(null);
                if (agentKey == null) {
                    log.warn("agent '{}' has no public key (no mTLS cert); sealing the backup token with the "
                            + "INSECURE plain1: dev fallback — enable mTLS for end-to-end encryption", agentId);
                }
                String sealed = envelope.seal(agentKey, dest.token().getBytes(StandardCharsets.UTF_8));
                command = Command.ConfigureBackupDestination.of(dest.repoUrl(), dest.branch(), dest.username(),
                        sealed);
            }
            Result result = agent.execute(command, EventSink.NOOP);
            boolean ok = result instanceof Result.BackupDestinationSet;
            if (!ok) {
                log.warn("agent '{}' returned {} for the backup destination", agentId,
                        result.getClass().getSimpleName());
            }
            return new Delivery(agentId, ok, ok ? null : "unexpected result");
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("could not set the backup destination on agent '{}': {}", agentId, detail);
            return new Delivery(agentId, false, detail);
        }
    }

    /**
     * Called when an agent connects. Runs asynchronously and stays silent when nothing is configured, so a
     * deployment that does not use remote backups pays nothing for this.
     */
    public void onAgentConnected(String tenantId, String agentId) {
        executor.submit(() -> {
            if (destinations.get(tenantId).isEmpty()) {
                return;
            }
            Delivery delivery = deliver(tenantId, agentId);
            if (delivery.delivered()) {
                log.info("agent '{}' picked up the organization's backup destination on connect", agentId);
            }
        });
    }
}
