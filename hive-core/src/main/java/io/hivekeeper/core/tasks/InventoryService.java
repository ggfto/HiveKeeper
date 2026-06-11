package io.hivekeeper.core.tasks;

import io.hivekeeper.core.api.Event;
import io.hivekeeper.core.api.EventSink;
import io.hivekeeper.core.drivers.Driver;
import io.hivekeeper.core.drivers.DriverRegistry;
import io.hivekeeper.core.drivers.HiveOsCapture;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.session.CommandRunner;
import io.hivekeeper.core.transport.SshSession;
import java.io.IOException;
import java.util.UUID;

/**
 * Connects, fingerprints the device, runs the driver's inventory commands, and parses a {@link Device}.
 * Emits progress through the {@link EventSink} so any front-end can show it live.
 */
public final class InventoryService {

    private final DriverRegistry drivers;

    public InventoryService(DriverRegistry drivers) {
        this.drivers = drivers;
    }

    public Device collect(UUID commandId, DeviceRef ref, SshSession session, EventSink sink) throws IOException {
        CommandRunner runner = new CommandRunner(session);

        sink.emit(new Event.Progress(commandId, ref.id(), "Reading show version", 10));
        String version = runner.run("show version");

        Driver driver = drivers.select(version)
                .orElseThrow(() -> new IOException("No driver recognizes this device from 'show version'"));
        sink.emit(new Event.Log(commandId, ref.id(), "Matched driver: " + driver.id()));

        sink.emit(new Event.Progress(commandId, ref.id(), "Reading interfaces", 45));
        String iface = runner.run(driver.showInterfaceCommand());

        sink.emit(new Event.Progress(commandId, ref.id(), "Reading stations", 75));
        String stations = runner.run(driver.showStationCommand());

        Device device = driver.parseDevice(ref.id(), new HiveOsCapture(version, iface, stations));
        sink.emit(new Event.Progress(commandId, ref.id(), "Parsed inventory", 100));
        return device;
    }
}
