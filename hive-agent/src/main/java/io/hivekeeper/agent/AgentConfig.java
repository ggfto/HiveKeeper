package io.hivekeeper.agent;

import java.net.URI;

/**
 * Agent configuration, sourced from environment variables (container/service friendly):
 * <ul>
 *   <li>{@code HIVEKEEPER_GATEWAY_URL} — the gateway WebSocket URL (e.g. wss://gw.example/agent)</li>
 *   <li>{@code HIVEKEEPER_AGENT_ID} — stable agent identifier (defaults to the hostname)</li>
 *   <li>{@code HIVEKEEPER_DEFAULT_USER} / {@code HIVEKEEPER_DEFAULT_PASSWORD} — fallback device creds
 *       used by the v1 lab credential provider (production resolves per-device from a local keystore)</li>
 *   <li>{@code HIVEKEEPER_BACKUP_DIR} — local git backup directory</li>
 * </ul>
 */
public record AgentConfig(URI gatewayUri, String agentId, String defaultUser, String defaultPassword,
                          String backupDir) {

    public static AgentConfig fromEnv() {
        String url = env("HIVEKEEPER_GATEWAY_URL", "ws://127.0.0.1:8090/agent");
        String id = env("HIVEKEEPER_AGENT_ID", defaultAgentId());
        String user = env("HIVEKEEPER_DEFAULT_USER", "admin");
        String password = env("HIVEKEEPER_DEFAULT_PASSWORD", "");
        String backupDir = env("HIVEKEEPER_BACKUP_DIR", "hivekeeper-backups");
        return new AgentConfig(URI.create(url), id, user, password, backupDir);
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
