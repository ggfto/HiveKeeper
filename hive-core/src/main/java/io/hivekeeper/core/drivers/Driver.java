package io.hivekeeper.core.drivers;

import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;

/**
 * Vendor/OS driver SPI — the project's key extensibility seam. Supporting a new AP family (e.g. an
 * AP410 in WiNG persona, or another vendor entirely) means adding one {@code Driver} implementation,
 * registered via {@link java.util.ServiceLoader}, with no change to the engine or tasks.
 *
 * <p>A driver knows two things: how to recognize a device, and how to translate its CLI output into
 * the vendor-neutral {@link Device} model. The command vocabulary is overridable per driver.
 */
public interface Driver {

    String id();

    /** True if this driver recognizes the device from its {@code show version} output. */
    boolean supports(String showVersionOutput);

    Device parseDevice(DeviceId id, HiveOsCapture capture);

    // --- CLI command vocabulary (overridable per driver) ---

    default String showVersionCommand() {
        return "show version";
    }

    default String showInterfaceCommand() {
        return "show interface";
    }

    default String showStationCommand() {
        return "show station";
    }

    default String runningConfigCommand(boolean includeSecrets) {
        return includeSecrets ? "show running-config password" : "show running-config";
    }

    /** The separate, TPM-backed PPSK/users channel. */
    default String usersConfigCommand() {
        return "show running-config users password";
    }
}
