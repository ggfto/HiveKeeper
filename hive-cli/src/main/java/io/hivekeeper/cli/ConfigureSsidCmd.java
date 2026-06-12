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
        description = "Create or remove a WPA2-PSK SSID (optionally on a VLAN) and save the config.")
final class ConfigureSsidCmd implements Callable<Integer> {

    @CommandLine.Mixin
    ConnectionOptions conn;

    @CommandLine.Option(names = {"-n", "--name"}, required = true, description = "SSID name")
    String name;

    @CommandLine.Option(names = {"-k", "--psk"}, description = "WPA2 passphrase (required unless --remove)")
    String psk;

    @CommandLine.Option(names = "--vlan", description = "VLAN id to bind via a user-profile")
    Integer vlan;

    @CommandLine.Option(names = "--remove", description = "Remove the SSID instead of creating it")
    boolean remove;

    @Override
    public Integer call() {
        if (!remove && (psk == null || psk.isBlank())) {
            System.err.println("--psk is required to create an SSID");
            return 2;
        }
        SsidSpec spec = remove ? SsidSpec.removal(name) : SsidSpec.create(name, psk, vlan);
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
