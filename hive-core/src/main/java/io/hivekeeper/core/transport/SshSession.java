package io.hivekeeper.core.transport;

import java.io.IOException;

/**
 * A live SSH session to one device. Lives entirely on-prem and is never referenced by a DTO — the
 * engine opens it, runs commands, and closes it within a single {@code execute} call.
 */
public interface SshSession extends AutoCloseable {

    /** Run a single CLI command and return its full textual output. */
    String exec(String command) throws IOException;

    @Override
    void close();
}
