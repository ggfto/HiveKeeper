package io.hivekeeper.cli;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.engine.HiveCore;
import io.hivekeeper.core.model.DeviceRef;
import picocli.CommandLine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "restore", mixinStandardHelpOptions = true,
        description = "Re-apply a saved running-config by replaying its lines (additive) and saving.")
final class RestoreCmd implements Callable<Integer> {

    @CommandLine.Mixin
    ConnectionOptions conn;

    @CommandLine.Option(names = {"-f", "--from"}, required = true,
            description = "Path to a saved running-config.txt (e.g. from a backup)")
    Path from;

    @CommandLine.Option(names = "--no-save", description = "Apply without persisting via 'save config'")
    boolean noSave;

    @Override
    public Integer call() {
        String runningConfig;
        try {
            runningConfig = Files.readString(from);
        } catch (IOException e) {
            System.err.println("cannot read " + from + ": " + e.getMessage());
            return 2;
        }
        DeviceRef ref = DeviceRef.ssh(conn.host, conn.port);
        Engine engine = HiveCore.localEngine(CliSupport.credentials(conn), CliSupport.NO_STORE);
        try {
            Result result = engine.execute(Command.RestoreConfig.of(ref, runningConfig, !noSave), new ConsoleEventSink());
            if (result instanceof Result.ConfigApplied applied) {
                System.out.printf("Restored %d line(s), saved=%s%n", applied.commands().size(), applied.saved());
                CliSupport.printConfigErrors(applied);
            }
            return 0;
        } catch (HiveException e) {
            System.err.println("restore failed: " + e.getMessage());
            return 1;
        }
    }
}
