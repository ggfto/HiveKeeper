package io.hivekeeper.cli;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.engine.HiveCore;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.model.SsidSpec;
import picocli.CommandLine;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "configure-ssid", mixinStandardHelpOptions = true,
        description = "Create or remove an SSID (optionally on a VLAN) and save the config.")
final class ConfigureSsidCmd implements Callable<Integer> {

    @CommandLine.Mixin
    ConnectionOptions conn;

    @CommandLine.Option(names = {"-n", "--name"}, required = true, description = "SSID name")
    String name;

    @CommandLine.Option(names = {"-k", "--psk"}, description = "Passphrase (required for keyed suites unless --remove)")
    String psk;

    @CommandLine.Option(names = "--vlan", description = "VLAN id to bind via a user-profile")
    Integer vlan;

    @CommandLine.Option(names = "--security", defaultValue = SsidSpec.WPA2_PSK,
            description = "Security suite: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})",
            completionCandidates = SecuritySuites.class)
    String security;

    @CommandLine.Option(names = "--radius-server", description = "RADIUS server IP/host (enterprise 802.1X suites)")
    String radiusServer;

    @CommandLine.Option(names = "--radius-secret", description = "RADIUS shared secret (enterprise 802.1X suites)")
    String radiusSecret;

    @CommandLine.Option(names = "--radius-auth-port", description = "RADIUS authentication port (optional)")
    Integer radiusAuthPort;

    @CommandLine.Option(names = "--remove", description = "Remove the SSID instead of creating it")
    boolean remove;

    /** The selectable suites, so picocli can list and tab-complete them. */
    /**
     * The suites {@link SsidSpec} accepts, derived from the model rather than restated here — a hardcoded
     * copy had already drifted, leaving OWE and WPA3 192-bit invisible on the command line after the model
     * gained them. Grouped keyless / preshared-key / enterprise, and sorted within each group so the help
     * text is stable between runs (the model holds them in unordered sets).
     */
    static final class SecuritySuites extends java.util.ArrayList<String> {
        SecuritySuites() {
            super(java.util.stream.Stream
                    .of(SsidSpec.KEYLESS_SUITES, SsidSpec.PSK_SUITES, SsidSpec.ENTERPRISE_SUITES)
                    .flatMap(group -> group.stream().sorted())
                    .toList());
        }
    }

    @Override
    public Integer call() {
        boolean enterprise = !remove && SsidSpec.ENTERPRISE_SUITES.contains(security);
        boolean keyed = !remove && SsidSpec.PSK_SUITES.contains(security);
        if (keyed && (psk == null || psk.isBlank())) {
            System.err.println("--psk is required to create a " + security + " SSID");
            return 2;
        }
        if (enterprise && (radiusServer == null || radiusServer.isBlank() || radiusSecret == null || radiusSecret.isBlank())) {
            System.err.println("--radius-server and --radius-secret are required for " + security);
            return 2;
        }
        SsidSpec spec;
        if (remove) {
            spec = SsidSpec.removal(name);
        } else if (enterprise) {
            spec = SsidSpec.createEnterprise(name, vlan, security,
                    new SsidSpec.RadiusSpec(radiusServer, radiusSecret, radiusAuthPort));
        } else {
            spec = SsidSpec.create(name, psk, vlan, security);
        }
        DeviceRef ref = DeviceRef.ssh(conn.host, conn.port);
        Engine engine = HiveCore.localEngine(CliSupport.credentials(conn), CliSupport.NO_STORE);
        try {
            Result result = engine.execute(Command.ConfigureSsid.of(ref, spec), new ConsoleEventSink());
            if (result instanceof Result.ConfigApplied applied) {
                System.out.printf("%s SSID '%s' (%d commands, saved=%s)%n",
                        remove ? "Removed" : "Configured", name, applied.commands().size(), applied.saved());
                CliSupport.printConfigErrors(applied);
            }
            return 0;
        } catch (HiveException e) {
            System.err.println("configure-ssid failed: " + e.getMessage());
            return 1;
        }
    }
}
