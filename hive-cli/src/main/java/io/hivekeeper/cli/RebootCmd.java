package io.hivekeeper.cli;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.engine.HiveCore;
import io.hivekeeper.core.model.DeviceRef;
import picocli.CommandLine;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "reboot", mixinStandardHelpOptions = true,
        description = "Reboot the device. It will be offline for a minute or two while it restarts.")
final class RebootCmd implements Callable<Integer> {

    @CommandLine.Mixin
    ConnectionOptions conn;

    @CommandLine.Option(names = "--yes", description = "Confirm the reboot (required; it is disruptive)")
    boolean yes;

    @Override
    public Integer call() {
        if (!yes) {
            System.err.println("Refusing to reboot without --yes (the AP goes offline for ~1-2 minutes).");
            return 2;
        }
        DeviceRef ref = DeviceRef.ssh(conn.host, conn.port);
        Engine engine = HiveCore.localEngine(CliSupport.credentials(conn), CliSupport.NO_STORE);
        try {
            Result result = engine.execute(Command.Reboot.of(ref), new ConsoleEventSink());
            if (result instanceof Result.ConfigApplied applied && !applied.outputs().isEmpty()) {
                System.out.println(applied.outputs().get(0).strip());
            }
            System.out.println("Reboot requested for " + conn.host + " — give it a minute before reconnecting.");
            return 0;
        } catch (HiveException e) {
            System.err.println("reboot failed: " + e.getMessage());
            return 1;
        }
    }
}
