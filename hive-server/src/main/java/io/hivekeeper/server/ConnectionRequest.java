package io.hivekeeper.server;

/** Request body for device operations. Credentials stay server-side (on-prem) — they are used to open
 *  the SSH session and are never echoed back or persisted by the server. */
public record ConnectionRequest(String host, Integer port, String user, String password, String dir) {
}
