package io.hivekeeper.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds the full command tree the way {@code Main} does. picocli validates every subcommand's options at
 * construction time, so this fails fast if a subcommand option ever collides with the shared
 * {@link ConnectionOptions} mixin (e.g. reusing {@code --password}) — a mistake the compiler can't catch.
 */
class CommandWiringTest {

    @Test
    void commandTreeBuildsWithoutOptionCollisions() {
        CommandLine cli = new CommandLine(new Main());
        assertNotNull(cli.getCommandSpec());

        var subs = cli.getSubcommands();
        assertTrue(subs.containsKey("configure-ssid"));
        assertTrue(subs.containsKey("configure-hive"));
        assertTrue(subs.containsKey("reboot"));
        assertTrue(subs.containsKey("restore"));
    }

    @Test
    void configureHivePasswordOptionDoesNotShadowSshPassword() {
        var hive = new CommandLine(new Main()).getSubcommands().get("configure-hive").getCommandSpec();

        // --password stays the SSH password (from the mixin); the hive secret is --hive-password.
        assertTrue(hive.optionsMap().containsKey("--hive-password"));
        assertTrue(hive.optionsMap().containsKey("--password"));
    }
}
