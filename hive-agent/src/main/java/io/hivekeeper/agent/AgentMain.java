package io.hivekeeper.agent;

import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.engine.HiveCore;
import io.hivekeeper.core.spi.BackupStore;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.spi.Credentials;
import io.hivekeeper.core.tasks.storage.GitBackupStore;
import io.hivekeeper.protocol.AgentRuntime;
import lombok.extern.slf4j.Slf4j;
import javax.net.ssl.SSLContext;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * The on-prem agent process: wires a real {@link io.hivekeeper.core.engine.LocalEngine} (sshj transport
 * + ServiceLoader drivers, credentials resolved locally) to an {@link AgentRuntime} driven over an
 * outbound {@link WebSocketFrameChannel}. Distributed as a Windows/Linux service and a container; it
 * dials out to the gateway and never exposes an inbound port on the LAN.
 */
@Slf4j
public final class AgentMain {

    private AgentMain() {
    }

    public static void main(String[] args) throws Exception {
        AgentConfig config = AgentConfig.fromEnv();
        log.info("starting HiveKeeper agent '{}' -> {} (mTLS={})",
                config.agentId(), config.gatewayUri(), config.mtlsEnabled());

        // Credentials are resolved HERE, on the agent. With a vault configured, each device's credRef maps
        // to its own secret locally; otherwise one default credential covers the fleet. Either way the cloud
        // only ever sent a reference.
        Credentials fallback = new Credentials(config.defaultUser(), config.defaultPassword());
        CredentialProvider credentials = config.credentialVault() == null
                ? new DefaultCredentialProvider(config.defaultUser(), config.defaultPassword())
                : VaultCredentialProvider.fromFile(Path.of(config.credentialVault()), fallback);
        log.info("credentials: {}", config.credentialVault() == null ? "single default" : "per-device vault");
        BackupStore backupStore = new GitBackupStore(Path.of(config.backupDir()));
        Engine engine = HiveCore.localEngine(credentials, backupStore);

        SSLContext sslContext = config.mtlsEnabled()
                ? TlsSupport.fromKeystores(config.tlsKeystore(), config.tlsKeystorePassword().toCharArray(),
                        config.tlsTruststore(), config.tlsTruststorePassword().toCharArray())
                : null;
        WebSocketFrameChannel channel = new WebSocketFrameChannel(config.gatewayUri(), sslContext);
        AgentRuntime agent = new AgentRuntime(engine, channel, config.agentId());

        agent.start();                          // register the job handler once
        channel.onConnected(agent::announce);   // re-announce on every (re)connect
        channel.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutting down agent");
            channel.close();
            agent.close();
        }));

        // Run until killed (service/container lifecycle).
        new CountDownLatch(1).await();
    }
}
