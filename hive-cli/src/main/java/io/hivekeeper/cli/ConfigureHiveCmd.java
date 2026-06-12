package io.hivekeeper.cli;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.engine.HiveCore;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.model.HiveSpec;
import picocli.CommandLine;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "configure-hive", mixinStandardHelpOptions = true,
        description = "Join the device to a hive/mesh (name + shared password) and save the config.")
final class ConfigureHiveCmd implements Callable<Integer> {

    @CommandLine.Mixin
    ConnectionOptions conn;

    @CommandLine.Option(names = {"-n", "--name"}, required = true, description = "Hive (mesh) name")
    String name;

    @CommandLine.Option(names = {"-w", "--hive-password"}, required = true,
            description = "Hive shared password (distinct from the SSH --password)")
    String password;

    @CommandLine.Option(names = "--interface", description = "Management interface to bind (default: mgt0)")
    String boundInterface;

    @Override
    public Integer call() {
        HiveSpec spec = new HiveSpec(name, password, boundInterface);
        DeviceRef ref = DeviceRef.ssh(conn.host, conn.port);
        Engine engine = HiveCore.localEngine(CliSupport.credentials(conn), CliSupport.NO_STORE);
        try {
            Result result = engine.execute(Command.ConfigureHive.of(ref, spec), new ConsoleEventSink());
            if (result instanceof Result.ConfigApplied applied) {
                System.out.printf("Joined hive '%s' (%d commands, saved=%s)%n",
                        name, applied.commands().size(), applied.saved());
                CliSupport.printConfigErrors(applied);
            }
            return 0;
        } catch (HiveException e) {
            System.err.println("configure-hive failed: " + e.getMessage());
            return 1;
        }
    }
}
