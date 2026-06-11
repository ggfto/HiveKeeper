package io.hivekeeper.core.transport;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * sshj-backed {@link SshSession} using one exec channel per command. HiveOS exposes a restricted,
 * IOS-like CLI; v0.1 assumes exec channels return clean output. If the week-1 spike against a live
 * AP230 shows the device needs an interactive shell with pager ({@code --More--}) handling, add a
 * shell-based session here — the {@link io.hivekeeper.core.session.CommandRunner} already strips pager
 * markers.
 */
final class SshjSession implements SshSession {

    private final SSHClient client;
    private final int commandTimeoutSeconds;

    SshjSession(SSHClient client, int commandTimeoutSeconds) {
        this.client = client;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
    }

    @Override
    public String exec(String command) throws IOException {
        try (Session session = client.startSession()) {
            Session.Command cmd = session.exec(command);
            String out = IOUtils.readFully(cmd.getInputStream()).toString(StandardCharsets.UTF_8);
            cmd.join(commandTimeoutSeconds, TimeUnit.SECONDS);
            return out;
        }
    }

    @Override
    public void close() {
        try {
            client.disconnect();
        } catch (IOException ignored) {
            // best-effort close
        }
    }
}
