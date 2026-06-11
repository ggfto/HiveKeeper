package io.hivekeeper.agent;

import java.net.URI;

/**
 * Agent configuration, sourced from environment variables (container/service friendly):
 * <ul>
 *   <li>{@code HIVEKEEPER_GATEWAY_URL} — gateway WebSocket URL (ws:// or wss://)</li>
 *   <li>{@code HIVEKEEPER_AGENT_ID} — stable agent identifier (defaults to the hostname)</li>
 *   <li>{@code HIVEKEEPER_DEFAULT_USER} / {@code HIVEKEEPER_DEFAULT_PASSWORD} — fallback device creds
 *       for the v1 lab provider (production resolves per-device from a local keystore)</li>
 *   <li>{@code HIVEKEEPER_BACKUP_DIR} — local git backup directory</li>
 *   <li>{@code HIVEKEEPER_TLS_KEYSTORE} (+ {@code _PASSWORD}) — client keystore for mTLS (PKCS12)</li>
 *   <li>{@code HIVEKEEPER_TLS_TRUSTSTORE} (+ {@code _PASSWORD}) — CA truststore (PKCS12)</li>
 * </ul>
 * mTLS is enabled when a keystore is configured (use a {@code wss://} gateway URL).
 */
public record AgentConfig(URI gatewayUri, String agentId, String defaultUser, String defaultPassword,
                          String backupDir, String tlsKeystore, String tlsKeystorePassword,
                          String tlsTruststore, String tlsTruststorePassword) {

    public boolean mtlsEnabled() {
        return tlsKeystore != null && !tlsKeystore.isBlank();
    }

    public static AgentConfig fromEnv() {
        return new AgentConfig(
                URI.create(env("HIVEKEEPER_GATEWAY_URL", "ws://127.0.0.1:8090/agent")),
                env("HIVEKEEPER_AGENT_ID", defaultAgentId()),
                env("HIVEKEEPER_DEFAULT_USER", "admin"),
                env("HIVEKEEPER_DEFAULT_PASSWORD", ""),
                env("HIVEKEEPER_BACKUP_DIR", "hivekeeper-backups"),
                env("HIVEKEEPER_TLS_KEYSTORE", null),
                env("HIVEKEEPER_TLS_KEYSTORE_PASSWORD", "changeit"),
                env("HIVEKEEPER_TLS_TRUSTSTORE", null),
                env("HIVEKEEPER_TLS_TRUSTSTORE_PASSWORD", "changeit"));
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
