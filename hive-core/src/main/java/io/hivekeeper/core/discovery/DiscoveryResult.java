package io.hivekeeper.core.discovery;

/** Outcome of probing one host during discovery. Serializable, no live handles. */
public record DiscoveryResult(String host, int port, boolean reachable, String sshBanner, boolean looksLikeSsh) {
}
