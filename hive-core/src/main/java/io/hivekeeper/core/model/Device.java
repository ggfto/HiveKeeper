package io.hivekeeper.core.model;

import lombok.Builder;
import java.util.List;

/**
 * Vendor-neutral inventory snapshot of an access point. Immutable, serializable, no live handles. A
 * record (so getters/equals/hashCode/toString/constructor are free) plus a Lombok {@code @Builder} for
 * readable construction — it has enough fields that positional construction is error-prone.
 */
@Builder
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
