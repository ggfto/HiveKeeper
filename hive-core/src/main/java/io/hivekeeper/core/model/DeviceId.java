package io.hivekeeper.core.model;

/** Stable logical identifier for a managed device. Carries no secrets and no live handles. */
public record DeviceId(String value) {

    public DeviceId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("DeviceId value must not be blank");
        }
    }

    public static DeviceId of(String value) {
        return new DeviceId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
