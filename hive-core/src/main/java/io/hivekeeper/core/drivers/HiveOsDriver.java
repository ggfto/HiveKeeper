package io.hivekeeper.core.drivers;

import io.hivekeeper.core.model.ConfigSnapshot;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.HiveSpec;
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

    // ── Firmware upgrade ────────────────────────────────────────────────────────────────────────────
    // LAB / UNTESTED (v0.1): this is the ONE place that holds the HiveOS firmware-upgrade vocabulary.
    // On IQ Engine / HiveOS, `save image <url>` downloads an image from a reachable TFTP/FTP/HTTP server
    // and writes it to the alternate boot partition; a subsequent `reboot` activates it. This flow has
    // NOT been validated against a live AP — confirm the exact command, the URL forms your firmware
    // accepts, and the download/activation semantics for your HiveOS version before any production use.
    private static final String SAVE_IMAGE = "save image ";

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
    public String upgradeFirmware(DeviceId id, CliExecutor exec, String imageUrl, boolean reboot,
                                  ProgressReporter progress) throws IOException {
        progress.report(20, "Downloading and writing image: " + imageUrl);
        String output = exec.run(SAVE_IMAGE + imageUrl);
        if (!reboot) {
            progress.report(100, "Image saved; a reboot is required to activate it");
            return output;
        }
        progress.report(80, "Rebooting to activate the new image");
        try {
            output = output + System.lineSeparator() + exec.run("reboot");
        } catch (IOException e) {
            // The AP tore the SSH channel down as it restarted — that is the reboot succeeding, not a fault.
            output = output + System.lineSeparator() + "reboot initiated (session closed by device)";
        }
        progress.report(100, "Firmware upgrade initiated; verify the version once the AP is back online");
        return output;
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

    @Override
    public List<String> hiveCommands(HiveSpec spec) {
        // HiveOS: declare the hive, set its shared key, then bind the management interface to it.
        List<String> commands = new ArrayList<>(3);
        commands.add("hive " + spec.name());
        commands.add("hive " + spec.name() + " password " + spec.password());
        commands.add("interface " + spec.boundInterface() + " hive " + spec.name());
        return commands;
    }

    @Override
    public List<String> adminPasswordCommands(String username, String newPassword) {
        // Grammar AND password policy confirmed live on an AP230 (HiveOS 10.6 / IQ Engine) via `?` help,
        // `show admin`, and an end-to-end change test:
        //   admin root-admin <name> password <string>
        // The device REJECTS a non-compliant password ("password is not valid"), so validate up front to give
        // a clear error instead of a device round-trip. The default 'admin' user is a root-admin; this targets
        // that role. A read-write admin would use `admin read-write <name> ...` — use the Advanced CLI for that.
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required to change the admin password");
        }
        validateAdminPassword(username, newPassword);
        return List.of("admin root-admin " + username + " password " + newPassword);
    }

    /** The live-confirmed HiveOS admin-password policy: 8-32 chars, ≥1 digit, ≥1 uppercase, and not equal to
     *  the username or the literal "password". */
    private static void validateAdminPassword(String username, String password) {
        if (password == null || password.length() < 8 || password.length() > 32) {
            throw new IllegalArgumentException("HiveOS admin password must be 8-32 characters");
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            throw new IllegalArgumentException("HiveOS admin password must contain at least one number");
        }
        if (password.chars().noneMatch(Character::isUpperCase)) {
            throw new IllegalArgumentException("HiveOS admin password must contain at least one uppercase letter");
        }
        if (password.equalsIgnoreCase(username) || password.equalsIgnoreCase("password")) {
            throw new IllegalArgumentException("HiveOS admin password must differ from the username and 'password'");
        }
    }
}
