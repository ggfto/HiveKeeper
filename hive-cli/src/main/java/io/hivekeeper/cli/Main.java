package io.hivekeeper.cli;

import picocli.CommandLine;

/**
 * HiveKeeper command-line entry point. Talks only to the engine facade and DTOs — it never touches
 * transport, session, or driver internals.
 */
@CommandLine.Command(
        name = "hivekeeper",
        mixinStandardHelpOptions = true,
        versionProvider = Main.ManifestVersion.class,
        description = "Manage standalone Aerohive / Extreme HiveOS access points over SSH.",
        subcommands = {InventoryCmd.class, BackupCmd.class, CaptureCmd.class, DiscoverCmd.class,
                ConfigureSsidCmd.class, ConfigureHiveCmd.class, RebootCmd.class, RestoreCmd.class,
                FirmwareUpgradeCmd.class})
public final class Main {

    /** Reports the version stamped into the jar manifest at build time (see hive-cli/build.gradle.kts), so
     *  {@code --version} tracks the released version. Falls back to {@code (dev)} when run from classes. */
    static final class ManifestVersion implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String v = Main.class.getPackage().getImplementationVersion();
            return new String[] {"HiveKeeper " + (v == null ? "(dev)" : v)};
        }
    }

    public static void main(String[] args) {
        int code = new CommandLine(new Main()).execute(args);
        System.exit(code);
    }
}
