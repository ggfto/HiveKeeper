package io.hivekeeper.cli;

import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** SSH connection options shared by all subcommands via {@code @Mixin}. */
final class ConnectionOptions {

    @Parameters(index = "0", paramLabel = "HOST", description = "AP hostname or IP address")
    String host;

    @Option(names = {"-P", "--port"}, defaultValue = "22", description = "SSH port (default: 22)")
    int port;

    @Option(names = {"-u", "--user"}, defaultValue = "admin", description = "SSH username (default: admin)")
    String user;

    @Option(names = {"-p", "--password"},
            description = "SSH password (falls back to the HIVEKEEPER_PASSWORD environment variable)")
    String password;

    String resolvePassword() {
        if (password != null && !password.isEmpty()) {
            return password;
        }
        String env = System.getenv("HIVEKEEPER_PASSWORD");
        return env == null ? "" : env;
    }
}
