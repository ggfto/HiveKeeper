package io.hivekeeper.core.transport;

import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.spi.Credentials;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * sshj implementation of {@link SshTransport}. Opens a PTY-backed interactive shell session, because
 * HiveOS's restricted CLI does not service the SSH exec channel (it returns empty output). See
 * {@link SshjShellSession}.
 *
 * <p>Host keys are verified per {@link HostKeyPolicy}. The default is {@link HostKeyPolicy#TOFU}
 * (trust-on-first-use) against a managed {@code known_hosts} file, so front-ends are not promiscuous by
 * default; {@link HostKeyPolicy#ACCEPT_ALL} remains available as an explicit lab escape hatch.
 */
@Slf4j
public final class SshjTransport implements SshTransport {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_QUIET_MILLIS = 2_000;

    private final HostKeyPolicy hostKeyPolicy;
    private final Path knownHostsPath;
    private final int connectTimeoutMs;
    private final int quietMillis;

    /** Default transport: TOFU host-key verification against the per-user managed known_hosts file. */
    public SshjTransport() {
        this(HostKeyPolicy.TOFU, defaultKnownHostsPath(), DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_QUIET_MILLIS);
    }

    public SshjTransport(HostKeyPolicy hostKeyPolicy, Path knownHostsPath) {
        this(hostKeyPolicy, knownHostsPath, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_QUIET_MILLIS);
    }

    public SshjTransport(HostKeyPolicy hostKeyPolicy, Path knownHostsPath, int connectTimeoutMs, int quietMillis) {
        this.hostKeyPolicy = hostKeyPolicy;
        this.knownHostsPath = knownHostsPath;
        this.connectTimeoutMs = connectTimeoutMs;
        this.quietMillis = quietMillis;
    }

    /** {@code <user.home>/.hivekeeper/known_hosts} — the managed store used by CLI/server runs. */
    public static Path defaultKnownHostsPath() {
        return Path.of(System.getProperty("user.home", "."), ".hivekeeper", "known_hosts");
    }

    @Override
    public SshSession open(DeviceRef device, Credentials credentials) throws IOException {
        SSHClient client = new SSHClient(new DefaultConfig());
        client.addHostKeyVerifier(hostKeyVerifier());
        client.setConnectTimeout(connectTimeoutMs);
        log.debug("connecting to {}:{}", device.host(), device.port());
        client.connect(device.host(), device.port());
        try {
            client.authPassword(credentials.username(), credentials.password());
            log.debug("authenticated as '{}' on {}", credentials.username(), device.host());
            return new SshjShellSession(client, quietMillis);
        } catch (IOException e) {
            safeDisconnect(client);
            throw e;
        }
    }

    private HostKeyVerifier hostKeyVerifier() throws IOException {
        return switch (hostKeyPolicy) {
            case ACCEPT_ALL -> new PromiscuousVerifier();
            case TOFU -> new TofuKnownHostsVerifier(managedKnownHostsFile());
            case KNOWN_HOSTS -> new OpenSSHKnownHosts(managedKnownHostsFile());
        };
    }

    /** Ensure the parent dir exists (so a first-trust entry can be appended) and return the known_hosts file. */
    private java.io.File managedKnownHostsFile() throws IOException {
        Path parent = knownHostsPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return knownHostsPath.toFile();
    }

    private static void safeDisconnect(SSHClient client) {
        try {
            client.disconnect();
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
