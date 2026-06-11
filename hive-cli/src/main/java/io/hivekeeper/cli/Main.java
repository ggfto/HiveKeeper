package io.hivekeeper.cli;

import picocli.CommandLine;

/**
 * HiveKeeper command-line entry point. Talks only to the engine facade and DTOs — it never touches
 * transport, session, or driver internals.
 */
@CommandLine.Command(
        name = "hivekeeper",
        mixinStandardHelpOptions = true,
        version = "HiveKeeper 0.1.0-SNAPSHOT",
        description = "Manage standalone Aerohive / Extreme HiveOS access points over SSH.",
        subcommands = {InventoryCmd.class, BackupCmd.class})
public final class Main {

    public static void main(String[] args) {
        int code = new CommandLine(new Main()).execute(args);
        System.exit(code);
    }
}
