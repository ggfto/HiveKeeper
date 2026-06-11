package io.hivekeeper.core.transport;

/** How SSH host keys are verified when opening a session. */
public enum HostKeyPolicy {

    /**
     * Accept any host key. The v0.1 / lab default — these APs are on a trusted LAN and are often
     * freshly reset. TODO: switch to trust-on-first-use + known_hosts before any non-lab use.
     */
    ACCEPT_ALL,

    /** Verify against the user's {@code known_hosts}. */
    KNOWN_HOSTS
}
