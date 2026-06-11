package io.hivekeeper.core.model;

import java.util.List;

/** Vendor-neutral inventory snapshot of an access point. Immutable, serializable, no live handles. */
public record Device(
        DeviceId id,
        String hostname,
        String model,
        String serial,
        String firmwareVersion,
        String uptime,
        String managementIp,
        List<Radio> radios,
        List<Station> stations) {

    public Device {
        radios = radios == null ? List.of() : List.copyOf(radios);
        stations = stations == null ? List.of() : List.copyOf(stations);
    }
}
