package io.hivekeeper.cli;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.engine.HiveCore;
import io.hivekeeper.core.model.DeviceRef;
import picocli.CommandLine;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "firmware-upgrade", mixinStandardHelpOptions = true,
        description = "Upgrade the device firmware from a reachable image URL (TFTP/FTP/HTTP), then optionally "
                + "reboot to activate it. LAB/UNTESTED in v0.1 — validate against your hardware first.")
final class FirmwareUpgradeCmd implements Callable<Integer> {

    @CommandLine.Mixin
    ConnectionOptions conn;

    @CommandLine.Option(names = {"-f", "--image-url"}, required = true,
            description = "URL the AP can fetch the firmware image from, e.g. tftp://10.0.0.5/AP230.img")
    String imageUrl;

    @CommandLine.Option(names = "--no-reboot",
            description = "Download/stage the image without rebooting to activate it")
    boolean noReboot;

    @Override
    public Integer call() {
        DeviceRef ref = DeviceRef.ssh(conn.host, conn.port);
        Engine engine = HiveCore.localEngine(CliSupport.credentials(conn), CliSupport.NO_STORE);
        try {
            Result result = engine.execute(Command.FirmwareUpgrade.of(ref, imageUrl, !noReboot), new ConsoleEventSink());
            if (result instanceof Result.FirmwareUpgraded fw) {
                System.out.printf("Firmware upgrade requested for %s from %s (reboot=%s)%n",
                        ref.id(), fw.imageUrl(), fw.rebooting());
                if (fw.rebooting()) {
                    System.out.println("  the AP is restarting — re-run 'inventory' once it is back to confirm the version");
                } else {
                    System.out.println("  image staged; reboot the AP to activate the new firmware");
                }
            }
            return 0;
        } catch (HiveException e) {
            System.err.println("firmware upgrade failed: " + e.getMessage());
            return 1;
        }
    }
}
