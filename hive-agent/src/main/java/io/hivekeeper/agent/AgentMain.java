package io.hivekeeper.agent;

import io.hivekeeper.agent.radius.FreeRadiusFilesProvisioner;
import io.hivekeeper.agent.radius.RadiusProvisioner;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.crypto.EnvelopeCipher;
import io.hivekeeper.core.crypto.SecretCipher;
import io.hivekeeper.core.engine.HiveCore;
import io.hivekeeper.core.spi.BackupStore;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.spi.Credentials;
import io.hivekeeper.core.spi.PpskUserStore;
import io.hivekeeper.core.spi.SecretUnsealer;
import io.hivekeeper.core.spi.WritableCredentialProvider;
import io.hivekeeper.core.tasks.storage.GitBackupStore;
import io.hivekeeper.protocol.AgentRuntime;
import lombok.extern.slf4j.Slf4j;
import javax.net.ssl.SSLContext;
import java.nio.file.Path;
import java.security.PrivateKey;
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
        // only ever sent a reference. A configured vault is also WRITABLE — HiveKeeper can set/rotate a
        // credential from the UI: the gateway seals the secret to this agent's public key, and the agent
        // unseals it here with its mTLS private key before writing the (at-rest-encrypted) vault.
        Credentials fallback = new Credentials(config.defaultUser(), config.defaultPassword());
        SecretCipher vaultCipher = config.vaultKey() == null ? null : SecretCipher.fromBase64(config.vaultKey());
        // The agent's mTLS private key unseals secrets the gateway sealed to its public key (credentials, PSKs).
        PrivateKey agentKey = config.mtlsEnabled()
                ? TlsSupport.privateKey(config.tlsKeystore(), config.tlsKeystorePassword().toCharArray())
                : null;
        SecretUnsealer unsealer = new KeystoreSecretUnsealer(agentKey, new EnvelopeCipher());

        CredentialProvider credentials;
        WritableCredentialProvider writableCredentials = null;
        if (config.credentialVault() == null) {
            credentials = new DefaultCredentialProvider(config.defaultUser(), config.defaultPassword());
            log.info("credentials: single default (credential management disabled — set HIVEKEEPER_CREDENTIAL_VAULT)");
        } else {
            WritableVaultCredentialProvider vault = WritableVaultCredentialProvider.fromFile(
                    Path.of(config.credentialVault()), fallback, vaultCipher);
            credentials = vault;
            writableCredentials = vault;
            log.info("credentials: per-device vault (management enabled, at-rest {})",
                    vaultCipher != null ? "encrypted" : "PLAINTEXT");
        }

        // PPSK user store (Caminho B): mint/rotate/revoke Private PSKs locally and feed the co-located RADIUS
        // server. The PSK arrives sealed to this agent and is unsealed here, so the cloud never holds the key.
        PpskUserStore ppskUsers = null;
        if (config.ppskStore() != null) {
            RadiusProvisioner provisioner = config.radiusDir() == null ? null
                    : new FreeRadiusFilesProvisioner(Path.of(config.radiusDir()));
            ppskUsers = FilePpskUserStore.fromFile(Path.of(config.ppskStore()), vaultCipher, provisioner);
            log.info("PPSK store enabled (at-rest {}, RADIUS provisioning {})",
                    vaultCipher != null ? "encrypted" : "PLAINTEXT", provisioner != null ? "on" : "off");
        }

        BackupStore backupStore = new GitBackupStore(Path.of(config.backupDir()));
        Engine engine = HiveCore.localEngine(credentials, backupStore, writableCredentials, unsealer, ppskUsers);
        // Unwrap commands the gateway sealed to this agent's key (durable-job secrets) before the engine runs
        // them; plain commands pass straight through.
        engine = new UnsealingEngine(engine, agentKey, new EnvelopeCipher(), new io.hivekeeper.wire.JsonCodec());

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
