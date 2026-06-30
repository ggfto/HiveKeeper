package io.hivekeeper.agent;

import io.hivekeeper.core.transport.HostKeyPolicy;

import java.net.URI;

/**
 * Agent configuration, sourced from environment variables (container/service friendly):
 * <ul>
 *   <li>{@code HIVEKEEPER_GATEWAY_URL} — gateway WebSocket URL (ws:// or wss://)</li>
 *   <li>{@code HIVEKEEPER_AGENT_ID} — stable agent identifier (defaults to the hostname)</li>
 *   <li>{@code HIVEKEEPER_DEFAULT_USER} / {@code HIVEKEEPER_DEFAULT_PASSWORD} — fallback device creds used
 *       when a request has no {@code credRef} or it is not in the vault</li>
 *   <li>{@code HIVEKEEPER_CREDENTIAL_VAULT} — path to a per-device credential vault (properties:
 *       {@code <credRef>.user} / {@code <credRef>.password}); resolved locally, never sent by the cloud.
 *       When set, HiveKeeper can also manage credentials from the UI (writes this file).</li>
 *   <li>{@code HIVEKEEPER_VAULT_KEY} — base64 AES-256 key that encrypts vault passwords (and PPSK keys) at
 *       rest; without it they are read/written in plaintext (a logged warning)</li>
 *   <li>{@code HIVEKEEPER_PPSK_STORE} — path to the on-prem PPSK user store (properties); when set (and mTLS
 *       is configured for unsealing), HiveKeeper can mint/rotate/revoke Private PSKs from the UI
 *       ({@code ManagePpskUser}). The store feeds the co-located RADIUS server.</li>
 *   <li>{@code HIVEKEEPER_RADIUS_DIR} — directory the RADIUS provisioner writes its authorize file to
 *       (optional; PPSK keys are still stored without it, just not pushed to a RADIUS server)</li>
 *   <li>{@code HIVEKEEPER_BACKUP_DIR} — local git backup directory</li>
 *   <li>{@code HIVEKEEPER_TLS_KEYSTORE} (+ {@code _PASSWORD}) — client keystore for mTLS (PKCS12)</li>
 *   <li>{@code HIVEKEEPER_TLS_TRUSTSTORE} (+ {@code _PASSWORD}) — CA truststore (PKCS12)</li>
 *   <li>{@code HIVEKEEPER_SSH_HOSTKEY} — SSH host-key policy: {@code tofu} (default, trust-on-first-use),
 *       {@code strict} (keys must be pre-seeded), or {@code accept-all} (lab escape hatch, no verification)</li>
 *   <li>{@code HIVEKEEPER_KNOWN_HOSTS} — path to the managed known_hosts file (default
 *       {@code ./hivekeeper-known_hosts}); used by {@code tofu}/{@code strict}</li>
 *   <li>{@code HIVEKEEPER_ENROLLMENT_TOKEN} / {@code HIVEKEEPER_ENROLLMENT_URL} — one-time token + gateway base
 *       URL for automated certificate bootstrap: when set and no keystore file exists yet, the agent generates
 *       a keypair, posts a CSR, and writes the issued keystore/truststore before connecting (see
 *       {@link EnrollmentBootstrap})</li>
 *   <li>{@code HIVEKEEPER_ENROLLMENT_CACERT} — optional PEM CA bundle to trust the gateway's HTTPS server cert
 *       during bootstrap (needed when the enrollment URL is {@code https://} and the CA is private)</li>
 * </ul>
 * mTLS is enabled when a keystore is configured (use a {@code wss://} gateway URL).
 */
public record AgentConfig(URI gatewayUri, String agentId, String defaultUser, String defaultPassword,
                          String credentialVault, String vaultKey, String ppskStore, String radiusDir,
                          String backupDir, String tlsKeystore, String tlsKeystorePassword,
                          String tlsTruststore, String tlsTruststorePassword,
                          HostKeyPolicy sshHostKeyPolicy, String knownHostsPath,
                          String enrollmentToken, String enrollmentUrl, String enrollmentCaCert) {

    public boolean mtlsEnabled() {
        return tlsKeystore != null && !tlsKeystore.isBlank();
    }

    /** True when an enrollment token + URL are configured, so the agent can bootstrap a cert if it lacks one. */
    public boolean enrollmentConfigured() {
        return enrollmentToken != null && !enrollmentToken.isBlank()
                && enrollmentUrl != null && !enrollmentUrl.isBlank();
    }

    public static AgentConfig fromEnv() {
        return new AgentConfig(
                URI.create(env("HIVEKEEPER_GATEWAY_URL", "ws://127.0.0.1:8090/agent")),
                env("HIVEKEEPER_AGENT_ID", defaultAgentId()),
                env("HIVEKEEPER_DEFAULT_USER", "admin"),
                env("HIVEKEEPER_DEFAULT_PASSWORD", ""),
                env("HIVEKEEPER_CREDENTIAL_VAULT", null),
                env("HIVEKEEPER_VAULT_KEY", null),
                env("HIVEKEEPER_PPSK_STORE", null),
                env("HIVEKEEPER_RADIUS_DIR", null),
                env("HIVEKEEPER_BACKUP_DIR", "hivekeeper-backups"),
                env("HIVEKEEPER_TLS_KEYSTORE", null),
                env("HIVEKEEPER_TLS_KEYSTORE_PASSWORD", "changeit"),
                env("HIVEKEEPER_TLS_TRUSTSTORE", null),
                env("HIVEKEEPER_TLS_TRUSTSTORE_PASSWORD", "changeit"),
                hostKeyPolicy(env("HIVEKEEPER_SSH_HOSTKEY", "tofu")),
                env("HIVEKEEPER_KNOWN_HOSTS", "hivekeeper-known_hosts"),
                env("HIVEKEEPER_ENROLLMENT_TOKEN", null),
                env("HIVEKEEPER_ENROLLMENT_URL", null),
                env("HIVEKEEPER_ENROLLMENT_CACERT", null));
    }

    /** Map the {@code HIVEKEEPER_SSH_HOSTKEY} value to a {@link HostKeyPolicy} (unknown values fall back to TOFU). */
    private static HostKeyPolicy hostKeyPolicy(String value) {
        return switch (value.trim().toLowerCase()) {
            case "accept-all", "accept_all", "promiscuous" -> HostKeyPolicy.ACCEPT_ALL;
            case "strict", "known-hosts", "known_hosts" -> HostKeyPolicy.KNOWN_HOSTS;
            default -> HostKeyPolicy.TOFU;
        };
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String defaultAgentId() {
        String host = System.getenv("COMPUTERNAME");
        if (host == null || host.isBlank()) {
            host = System.getenv("HOSTNAME");
        }
        return host == null || host.isBlank() ? "hive-agent" : host;
    }
}
