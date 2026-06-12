package io.hivekeeper.core.drivers;

import io.hivekeeper.core.model.ConfigSnapshot;
import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;
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

    /** Captures the device configuration (and, per {@code scope}, the separate user/PPSK channel). */
    ConfigSnapshot captureConfig(DeviceId id, CliExecutor exec, ConfigScope scope, ProgressReporter progress)
            throws IOException;

    /** Applies configuration CLI lines, optionally persisting them, returning each line's output. */
    List<String> applyConfig(DeviceId id, CliExecutor exec, List<String> commands, boolean save,
                             ProgressReporter progress) throws IOException;

    /** Translates a vendor-neutral {@link SsidSpec} into this device's CLI lines (create or remove). */
    List<String> ssidCommands(SsidSpec spec);
}
