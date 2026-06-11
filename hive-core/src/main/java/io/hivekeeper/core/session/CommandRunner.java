package io.hivekeeper.core.session;

import io.hivekeeper.core.transport.SshSession;
import java.io.IOException;

/**
 * Runs a CLI command over a session and normalizes the output (trailing prompts, pager artifacts).
 * v0.1 relies on exec channels, which return clean output; the pager-stripping hook is here for the
 * interactive-shell path that the week-1 spike may prove necessary on some firmware.
 */
public final class CommandRunner {

    private final SshSession session;

    public CommandRunner(SshSession session) {
        this.session = session;
    }

    public String run(String cliCommand) throws IOException {
        return stripPager(session.exec(cliCommand));
    }

    /** Removes HiveOS pager markers like {@code --More--} that appear when an interactive shell pages. */
    static String stripPager(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("(?m)^.*--More--.*$\\R?", "");
    }
}
