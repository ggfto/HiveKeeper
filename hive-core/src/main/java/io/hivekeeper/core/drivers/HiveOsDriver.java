package io.hivekeeper.core.drivers;

import io.hivekeeper.core.model.ConfigSnapshot;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.SsidSpec;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Driver for Aerohive / Extreme HiveOS (IQ Engine) access points. AP230 / AP250 / AP630 share this CLI
 * grammar; an AP410C in WiNG persona would need its own driver. This class owns the HiveOS command
 * vocabulary and parsing; nothing HiveOS-specific leaks into the {@link Driver} SPI.
 */
@Slf4j
public final class HiveOsDriver implements Driver {

    private static final String SHOW_VERSION = "show version";
    private static final String SHOW_HW_INFO = "show hw-info";
    private static final String SHOW_INTERFACE_MGT0 = "show interface mgt0";
    private static final String SHOW_INTERFACE = "show interface";
    private static final String SHOW_STATION = "show station";
    private static final String RUNNING_CONFIG = "show running-config";
    private static final String RUNNING_CONFIG_SECRETS = "show running-config password";
    private static final String RUNNING_CONFIG_USERS = "show running-config users password";

    @Override
    public String id() {
        return "hiveos";
    }

    @Override
    public boolean recognizes(CliExecutor exec) throws IOException {
        boolean match = isHiveOs(exec.run(SHOW_VERSION));
        log.debug("hiveos recognizes={}", match);
        return match;
    }

    static boolean isHiveOs(String versionOutput) {
        if (versionOutput == null) {
            return false;
        }
        String v = versionOutput.toLowerCase();
        return v.contains("hiveos") || v.contains("iq engine") || v.contains("aerohive");
    }

    @Override
    public Device inventory(DeviceId id, CliExecutor exec, ProgressReporter progress) throws IOException {
        progress.report(10, "Reading show version");
        String version = exec.run(SHOW_VERSION);
        progress.report(35, "Reading hardware info");
        String hwInfo = exec.run(SHOW_HW_INFO);
        progress.report(55, "Reading management interface");
        String mgt0 = exec.run(SHOW_INTERFACE_MGT0);
        progress.report(70, "Reading interfaces");
        String iface = exec.run(SHOW_INTERFACE);
        progress.report(90, "Reading stations");
        String stations = exec.run(SHOW_STATION);

        Device device = HiveOsParser.parse(id, new HiveOsCapture(version, hwInfo, mgt0, iface, stations));
        progress.report(100, "Parsed inventory");
        return device;
    }

    @Override
    public ConfigSnapshot captureConfig(DeviceId id, CliExecutor exec, ConfigScope scope, ProgressReporter progress)
            throws IOException {
        progress.report(10, "Fingerprinting device");
        String version = exec.run(SHOW_VERSION);
        String firmware = HiveOsParser.parse(id, new HiveOsCapture(version, "", "", "", "")).firmwareVersion();

        progress.report(45, "Capturing running-config");
        String running = exec.run(scope.includeSecrets() ? RUNNING_CONFIG_SECRETS : RUNNING_CONFIG);

        String users = null;
        if (scope.includeUsers()) {
            progress.report(70, "Capturing PPSK users (separate channel)");
            try {
                users = exec.run(RUNNING_CONFIG_USERS);
            } catch (IOException e) {
                log.warn("PPSK/users channel unavailable: {}", e.getMessage());
            }
        }

        progress.report(100, "Config captured");
        // Timestamp is stamped here for simplicity; if deterministic time is ever needed in tests,
        // inject a Clock via a driver context.
        return new ConfigSnapshot(id, running, users, firmware, Instant.now());
    }

    @Override
    public List<String> applyConfig(DeviceId id, CliExecutor exec, List<String> commands, boolean save,
                                    ProgressReporter progress) throws IOException {
        List<String> outputs = new ArrayList<>(commands.size() + 1);
        int total = Math.max(1, commands.size() + (save ? 1 : 0));
        int done = 0;
        for (String command : commands) {
            progress.report((int) Math.round(++done * 100.0 / total), "Applying: " + command);
            outputs.add(exec.run(command));
        }
        if (save) {
            progress.report(100, "Saving config");
            outputs.add(exec.run("save config"));
        }
        return outputs;
    }

    @Override
    public List<String> ssidCommands(SsidSpec spec) {
        String name = spec.name();
        List<String> commands = new ArrayList<>();
        if (spec.remove()) {
            // HiveOS negates a config line by prefixing the WHOLE line with "no". Unbind from the radios
            // first, then drop the ssid, then its security-object and user-profile.
            commands.add("no interface wifi0 ssid " + name);
            commands.add("no interface wifi1 ssid " + name);
            commands.add("no ssid " + name);
            commands.add("no security-object " + name);
            commands.add("no user-profile " + name);
            return commands;
        }
        commands.add("security-object " + name);
        commands.add("security-object " + name + " security protocol-suite wpa2-aes-psk ascii-key " + spec.passphrase());
        if (spec.vlan() != null) {
            commands.add("user-profile " + name + " qos-policy def-user-qos vlan-id " + spec.vlan()
                    + " attribute " + spec.vlan());
            commands.add("security-object " + name + " default-user-profile-attr " + spec.vlan());
        }
        commands.add("ssid " + name);
        commands.add("ssid " + name + " security-object " + name);
        commands.add("interface wifi0 ssid " + name);
        commands.add("interface wifi1 ssid " + name);
        return commands;
    }
}
