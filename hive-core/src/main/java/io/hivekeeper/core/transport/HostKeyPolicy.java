package io.hivekeeper.core.transport;

/** How SSH host keys are verified when opening a session. */
public enum HostKeyPolicy {

    /**
     * Accept any host key — no verification. An explicit lab escape hatch only (one-off connects to a freshly
     * reset AP on a trusted LAN). Never use outside the lab: it offers no protection against a man-in-the-middle.
     */
    ACCEPT_ALL,

    /**
     * Trust-on-first-use against a managed {@code known_hosts} file: the first time a host is seen its key is
     * recorded and accepted; every later connect must match the recorded key, and a mismatch is refused
     * (possible MITM — or a legitimately reset/reflashed AP, recoverable by clearing the host's entry).
     * The pragmatic default.
     */
    TOFU,

    /**
     * Strict verification against a managed {@code known_hosts} file: keys must already be present (pre-seeded);
     * an unknown host or a changed key is refused. No first-use add — for high-assurance sites.
     */
    KNOWN_HOSTS
}
