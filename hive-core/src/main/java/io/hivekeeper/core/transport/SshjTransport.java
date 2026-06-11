package io.hivekeeper.core.transport;

import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.spi.Credentials;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import java.io.IOException;

/** sshj implementation of {@link SshTransport}. */
public final class SshjTransport implements SshTransport {

    private final HostKeyPolicy hostKeyPolicy;
    private final int connectTimeoutMs;
    private final int commandTimeoutSeconds;

    public SshjTransport() {
        this(HostKeyPolicy.ACCEPT_ALL, 10_000, 30);
    }

    public SshjTransport(HostKeyPolicy hostKeyPolicy, int connectTimeoutMs, int commandTimeoutSeconds) {
        this.hostKeyPolicy = hostKeyPolicy;
        this.connectTimeoutMs = connectTimeoutMs;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
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
        client.connect(device.host(), device.port());
        try {
            client.authPassword(credentials.username(), credentials.password());
        } catch (IOException e) {
            safeDisconnect(client);
            throw e;
        }
        return new SshjSession(client, commandTimeoutSeconds);
    }

    private static void safeDisconnect(SSHClient client) {
        try {
            client.disconnect();
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
