package io.hivekeeper.core.api;

import io.hivekeeper.core.model.DeviceId;
import java.util.UUID;

/**
 * A failure that also carries its context as data (the originating commandId + deviceId), mirroring
 * the {@link Event.Failed} that the engine emits before throwing. In-process callers catch this;
 * remote callers reconstruct the same failure from the serialized event.
 */
public class HiveException extends RuntimeException {

    private final transient UUID commandId;
    private final transient DeviceId deviceId;

    public HiveException(UUID commandId, DeviceId deviceId, String message, Throwable cause) {
        super(message, cause);
        this.commandId = commandId;
        this.deviceId = deviceId;
    }

    public UUID commandId() {
        return commandId;
    }

    public DeviceId deviceId() {
        return deviceId;
    }
}
