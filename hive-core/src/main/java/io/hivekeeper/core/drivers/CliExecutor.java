package io.hivekeeper.core.drivers;

import java.io.IOException;

/**
 * Runs a single CLI command against a device and returns its textual output. This is the ONLY thing a
 * {@link Driver} needs in order to talk to a device — it deliberately knows nothing about SSH, shells,
 * or sessions. That decoupling is what lets a driver be unit-tested with a fake executor, and what lets
 * the same driver run locally (in-process) or remotely (driven by a cloud-dispatched agent).
 */
@FunctionalInterface
public interface CliExecutor {
    String run(String command) throws IOException;
}
