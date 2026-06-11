package io.hivekeeper.core.transport;

import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.spi.Credentials;
import java.io.IOException;

/**
 * Opens SSH sessions to devices. Injectable so the engine can be unit-tested with a fake transport
 * (no real network I/O), and so the legacy-cipher fallback (mwiede/jsch) can be a drop-in alternative.
 */
public interface SshTransport {

    SshSession open(DeviceRef device, Credentials credentials) throws IOException;
}
