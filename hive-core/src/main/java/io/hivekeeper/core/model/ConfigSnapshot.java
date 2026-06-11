package io.hivekeeper.core.model;

import java.time.Instant;

/**
 * A captured device configuration. May contain secrets (the {@code show running-config password}
 * output) plus the separate PPSK/users channel. Produced on-prem and persisted via a BackupStore;
 * never shipped to a cloud control plane in cleartext.
 *
 * <p>Note the HiveOS caveats this models: {@code runningConfig} is NOT a byte-complete dump (default
 * values are omitted) and {@code usersConfig} is a distinct TPM-backed channel that may be absent.
 */
public record ConfigSnapshot(
        DeviceId deviceId,
        String runningConfig,
        String usersConfig,        // nullable — separate channel, may be unavailable
        String firmwareVersion,    // nullable
        Instant capturedAt) {
}
