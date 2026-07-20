package io.hivekeeper.core.drivers;

import io.hivekeeper.core.model.ChannelScan;
import io.hivekeeper.core.model.ConfigSnapshot;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
import io.hivekeeper.core.model.HiveSpec;
import io.hivekeeper.core.model.SsidSpec;
import java.io.IOException;
import java.util.List;

/**
 * Vendor/OS driver SPI — the project's key extensibility seam. Supporting a new device family (another
 * HiveOS-incompatible vendor, an AP410 in WiNG persona, etc.) means adding one {@code Driver}
 * implementation registered via {@link java.util.ServiceLoader}; nothing in the engine or transport
 * changes.
 *
 * <p>Crucially, a driver owns BOTH which CLI commands to run AND how to parse them. It receives only a
 * {@link CliExecutor}, so no vendor-specific command vocabulary or capture type leaks into the shared
 * contract. This is what makes the design genuinely multi-model rather than HiveOS-shaped.
 */
public interface Driver {

    /** Stable identifier, e.g. {@code "hiveos"}. */
    String id();

    /** Runs whatever probe this driver needs (e.g. {@code show version}) and reports whether it
     *  recognizes the device. */
    boolean recognizes(CliExecutor exec) throws IOException;

    /** Collects a vendor-neutral inventory snapshot. */
    Device inventory(DeviceId id, CliExecutor exec, ProgressReporter progress) throws IOException;

    /**
     * Reads the device's own assessment of the air around each radio: what every candidate channel would
     * cost, and which neighbouring BSSIDs it heard.
     *
     * <p>Read-only. Access points already run automatic channel selection and score the spectrum
     * continuously; this surfaces that measurement rather than re-deriving it, because the AP is the one
     * with the radio. Drivers whose platform exposes nothing comparable return an empty list.
     */
    default List<ChannelScan> channelScans(CliExecutor exec, ProgressReporter progress) throws IOException {
        return List.of();
    }

    /** Captures the device configuration (and, per {@code scope}, the separate user/PPSK channel). */
    ConfigSnapshot captureConfig(DeviceId id, CliExecutor exec, ConfigScope scope, ProgressReporter progress)
            throws IOException;

    /** Applies configuration CLI lines, optionally persisting them, returning each line's output. */
    List<String> applyConfig(DeviceId id, CliExecutor exec, List<String> commands, boolean save,
                             ProgressReporter progress) throws IOException;

    /**
     * The device's physical radio interfaces, named as its CLI expects them (HiveOS: {@code wifi0},
     * {@code wifi1}, {@code wifi2}, …), in the order the device reports them.
     *
     * <p>Asked of the device, never assumed. Radio count is not a constant of the platform: an AP230 and
     * an AP630 have two, an AP410C-1 has three (its second and third radios are both 5 GHz), and a future
     * model may well have four. Anything that binds an SSID to a hardcoded pair leaves the remaining
     * radios carrying nothing — with no error, because the AP accepted every line it was given.
     *
     * <p>Drivers that cannot enumerate radios return an empty list, which callers must treat as "unknown",
     * not as "none".
     */
    default List<String> radioInterfaces(CliExecutor exec) throws IOException {
        return List.of();
    }

    /**
     * Translates a vendor-neutral {@link SsidSpec} into this device's CLI lines (create or remove),
     * binding to exactly the radios in {@code radioInterfaces} (see {@link #radioInterfaces}).
     *
     * <p>Kept a pure function of its inputs, so the radio count it produces for is explicit at every call
     * site and directly testable — including counts no hardware in the lab has yet.
     */
    List<String> ssidCommands(SsidSpec spec, List<String> radioInterfaces);

    /** Translates a vendor-neutral {@link HiveSpec} into this device's hive/mesh CLI lines. */
    List<String> hiveCommands(HiveSpec spec);

    /**
     * Reboots the device. Most CLIs use {@code reboot}; the session is expected to drop as the device
     * restarts, so a dropped connection is treated as success rather than an error. Override if a vendor
     * needs a confirmation token or a different verb.
     */
    default String reboot(DeviceId id, CliExecutor exec) throws IOException {
        try {
            return exec.run("reboot");
        } catch (IOException e) {
            // The AP tore the SSH channel down as it restarted — that is the reboot succeeding, not a fault.
            return "reboot initiated (session closed by device)";
        }
    }

    /**
     * Upgrades the device firmware from an image at {@code imageUrl} (a TFTP/FTP/HTTP location the device
     * can reach), optionally rebooting to activate it. Returns the device's verbatim output. The default
     * refuses — firmware vocabulary is too vendor-specific to guess — so a driver must opt in by
     * overriding. When {@code reboot} is true the session is expected to drop as the device restarts,
     * which (as with {@link #reboot}) is treated as success rather than a fault.
     */
    default String upgradeFirmware(DeviceId id, CliExecutor exec, String imageUrl, boolean reboot,
                                   ProgressReporter progress) throws IOException {
        throw new UnsupportedOperationException("firmware upgrade not supported by driver");
    }

    /**
     * The CLI lines that change the admin {@code username}'s password to {@code newPassword} ON the device
     * itself. Used by {@code SetCredential} when the operator opts to rotate the password on the AP and not
     * only in HiveKeeper's vault. The default refuses — the exact grammar is vendor-specific and getting it
     * wrong can lock the device out — so a driver must opt in by overriding with grammar confirmed against
     * real hardware (never guessed). Returned lines are applied via {@link #applyConfig} with {@code save}.
     */
    default List<String> adminPasswordCommands(String username, String newPassword) {
        throw new UnsupportedOperationException("on-device password change not supported by driver");
    }
}
