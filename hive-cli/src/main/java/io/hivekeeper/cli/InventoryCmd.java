package io.hivekeeper.cli;

import io.hivekeeper.core.api.Command;
import io.hivekeeper.core.api.Engine;
import io.hivekeeper.core.api.HiveException;
import io.hivekeeper.core.api.Result;
import io.hivekeeper.core.engine.HiveCore;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.model.Radio;
import io.hivekeeper.core.model.Station;
import picocli.CommandLine;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "inventory", mixinStandardHelpOptions = true,
        description = "Connect to an AP over SSH and print its inventory (model, firmware, stations).")
final class InventoryCmd implements Callable<Integer> {

    @CommandLine.Mixin
    ConnectionOptions conn;

    @Override
    public Integer call() {
        DeviceRef ref = DeviceRef.ssh(conn.host, conn.port);
        Engine engine = HiveCore.localEngine(CliSupport.credentials(conn), CliSupport.NO_STORE);
        try {
            Result result = engine.execute(Command.Inventory.of(ref), new ConsoleEventSink());
            if (result instanceof Result.Inventory inv) {
                printDevice(inv.device());
            }
            return 0;
        } catch (HiveException e) {
            System.err.println("inventory failed: " + e.getMessage());
            return 1;
        }
    }

    private static void printDevice(Device d) {
        System.out.println();
        System.out.println("Device:   " + d.id());
        System.out.println("Model:    " + CliSupport.orDash(d.model()));
        System.out.println("Serial:   " + CliSupport.orDash(d.serial()));
        System.out.println("Firmware: " + CliSupport.orDash(d.firmwareVersion()));
        System.out.println("Hostname: " + CliSupport.orDash(d.hostname()));
        System.out.println("Uptime:   " + CliSupport.orDash(d.uptime()));
        System.out.println("Mgmt IP:  " + CliSupport.orDash(d.managementIp()));
        System.out.println("Hive:     " + CliSupport.orDash(d.hiveName()));
        System.out.println("Radios:   " + d.radios().size());
        for (Radio r : d.radios()) {
            String chan = r.channel() == null ? "-" : r.channel().toString();
            System.out.println("  - " + r.name() + " (" + CliSupport.orDash(r.mode()) + ", ch " + chan + ")");
        }
        System.out.println("Stations: " + d.stations().size());
        for (Station s : d.stations()) {
            String ip = s.ipAddress() == null ? "" : " (" + s.ipAddress() + ")";
            String ssid = s.ssid() == null ? "" : " on " + s.ssid();
            System.out.println("  - " + s.mac() + ip + ssid);
        }
    }
}
