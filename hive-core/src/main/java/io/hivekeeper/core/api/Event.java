package io.hivekeeper.core.api;

import io.hivekeeper.core.model.DeviceId;
import java.util.UUID;

/**
 * Lifecycle events streamed during command execution. The full lifecycle — including failure — is
 * expressed here as data (not only as a thrown exception), so it survives serialization across an
 * agent channel and drives a CLI spinner, an SSE stream, and a remote agent identically. Every event
 * echoes commandId + deviceId.
 */
public sealed interface Event {

    UUID commandId();

    DeviceId deviceId();

    record Started(UUID commandId, DeviceId deviceId, String label) implements Event {
    }

    record Progress(UUID commandId, DeviceId deviceId, String message, Integer percent) implements Event {
    }

    record Log(UUID commandId, DeviceId deviceId, String line) implements Event {
    }

    record Completed(UUID commandId, DeviceId deviceId, Result result) implements Event {
    }

    record Failed(UUID commandId, DeviceId deviceId, String error, String detail) implements Event {
    }
}
