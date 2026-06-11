package io.hivekeeper.core.drivers;

import io.hivekeeper.core.model.Device;
import io.hivekeeper.core.model.DeviceId;

/**
 * Driver for Aerohive / Extreme HiveOS (IQ Engine) access points. AP230 / AP250 / AP630 share this CLI
 * grammar; AP410C in WiNG persona does not and will need its own driver.
 */
public final class HiveOsDriver implements Driver {

    @Override
    public String id() {
        return "hiveos";
    }

    @Override
    public boolean supports(String showVersionOutput) {
        if (showVersionOutput == null) {
            return false;
        }
        String v = showVersionOutput.toLowerCase();
        return v.contains("hiveos") || v.contains("iq engine") || v.contains("aerohive");
    }

    @Override
    public Device parseDevice(DeviceId id, HiveOsCapture capture) {
        return HiveOsParser.parse(id, capture);
    }
}
