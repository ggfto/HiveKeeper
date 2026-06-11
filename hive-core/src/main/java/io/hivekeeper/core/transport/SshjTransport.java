package io.hivekeeper.core.transport;

import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.spi.Credentials;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;

/**
 * sshj implementation of {@link SshTransport}. Opens a PTY-backed interactive shell session, because
 * HiveOS's restricted CLI does not service the SSH exec channel (it returns empty output). See
 * {@link SshjShellSession}.
 */
@Slf4j
public final class SshjTransport implements SshTransport {

    private final HostKeyPolicy hostKeyPolicy;
    private final int connectTimeoutMs;
    private final int quietMillis;

    public SshjTransport() {
        this(HostKeyPolicy.ACCEPT_ALL, 10_000, 2_000);
    }

    public SshjTransport(HostKeyPolicy hostKeyPolicy, int connectTimeoutMs, int quietMillis) {
        this.hostKeyPolicy = hostKeyPolicy;
        this.connectTimeoutMs = connectTimeoutMs;
        this.quietMillis = quietMillis;
    }

    @Override
    public SshSession open(DeviceRef device, Credentials credentials) throws IOException {
        SSHClient client = new SSHClient(new DefaultConfig());
        if (hostKeyPolicy == HostKeyPolicy.ACCEPT_ALL) {
            client.addHostKeyVerifier(new PromiscuousVerifier());
        } else {
            client.loadKnownHosts();
        }
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

    private static void safeDisconnect(SSHClient client) {
        try {
            client.disconnect();
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
