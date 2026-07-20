package io.hivekeeper.agent;

import io.hivekeeper.core.transport.HostKeyPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the agent's configuration contract: a self-hosted operator configures it with a file, a container
 * configures it with the environment, and the environment wins where both speak.
 */
class AgentConfigTest {

    /** An environment lookup backed by a map, so no test touches the real process environment. */
    private static UnaryOperator<String> env(Map<String, String> vars) {
        return vars::get;
    }

    private static Properties props(String... keyValues) {
        Properties p = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            p.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return p;
    }

    @Test
    void readsEverySettingFromTheConfigFile() {
        AgentConfig config = AgentConfig.from(env(Map.of()), props(
                "gateway.url", "wss://hive.example.org:9443/agent",
                "agent.id", "site-a-agent",
                "default.user", "admin",
                "default.password", "s3cret",
                "credential.vault", "/var/lib/hivekeeper/vault.properties",
                "vault.key", "aGVsbG8=",
                "backup.dir", "/var/lib/hivekeeper/backups",
                "tls.keystore", "/etc/hivekeeper/agent.p12",
                "tls.keystore.password", "kspass",
                "tls.truststore", "/etc/hivekeeper/ca.p12",
                "ssh.hostkey", "strict",
                "enrollment.token", "one-time-token",
                "enrollment.url", "https://hive.example.org",
                "cert.renew.window.days", "14"));

        assertEquals("wss://hive.example.org:9443/agent", config.gatewayUri().toString());
        assertEquals("site-a-agent", config.agentId());
        assertEquals("s3cret", config.defaultPassword());
        assertEquals("/var/lib/hivekeeper/vault.properties", config.credentialVault());
        assertEquals("aGVsbG8=", config.vaultKey());
        assertEquals("/var/lib/hivekeeper/backups", config.backupDir());
        assertEquals("kspass", config.tlsKeystorePassword());
        assertEquals(HostKeyPolicy.KNOWN_HOSTS, config.sshHostKeyPolicy());
        assertEquals("one-time-token", config.enrollmentToken());
        assertEquals(14, config.renewWindowDays());
        assertTrue(config.mtlsEnabled());
        assertTrue(config.enrollmentConfigured());
        assertTrue(config.renewalEnabled());
    }

    @Test
    void environmentOverridesTheFile() {
        AgentConfig config = AgentConfig.from(
                env(Map.of("HIVEKEEPER_GATEWAY_URL", "wss://from-env/agent")),
                props("gateway.url", "wss://from-file/agent", "agent.id", "from-file"));

        assertEquals("wss://from-env/agent", config.gatewayUri().toString());
        // ...and a setting the environment is silent about still comes from the file.
        assertEquals("from-file", config.agentId());
    }

    @Test
    void aBlankEnvironmentValueDoesNotMaskTheFile() {
        // Compose writes `HIVEKEEPER_GATEWAY_URL: ${HIVEKEEPER_GATEWAY_URL:-}` — an unset var arrives as an
        // empty string, which must not shadow a real value in the file.
        AgentConfig config = AgentConfig.from(
                env(Map.of("HIVEKEEPER_GATEWAY_URL", "")), props("gateway.url", "wss://from-file/agent"));

        assertEquals("wss://from-file/agent", config.gatewayUri().toString());
    }

    @Test
    void fallsBackToDefaultsWithNeitherSource() {
        AgentConfig config = AgentConfig.from(env(Map.of()), new Properties());

        assertEquals("ws://127.0.0.1:8090/agent", config.gatewayUri().toString());
        assertEquals("hive-agent", config.agentId());
        assertEquals(HostKeyPolicy.TOFU, config.sshHostKeyPolicy());
        assertEquals(30, config.renewWindowDays());
        assertFalse(config.mtlsEnabled());
        assertFalse(config.enrollmentConfigured());
    }

    @Test
    void theAgentIdDefaultsToTheHostname() {
        assertEquals("edge-01", AgentConfig.from(env(Map.of("HOSTNAME", "edge-01")), new Properties()).agentId());
        assertEquals("EDGE-01",
                AgentConfig.from(env(Map.of("COMPUTERNAME", "EDGE-01")), new Properties()).agentId());
    }

    @Test
    void anUnparsableRenewWindowFallsBackInsteadOfCrashing() {
        AgentConfig config = AgentConfig.from(env(Map.of()), props(
                "cert.renew.window.days", "soon", "cert.renew.check.hours", "-3"));

        assertEquals(30, config.renewWindowDays());
        assertEquals(12, config.renewCheckHours());
    }

    @Test
    void fileKeysDeriveMechanicallyFromTheEnvironmentVariableNames() {
        assertEquals("gateway.url", AgentConfig.fileKey("HIVEKEEPER_GATEWAY_URL"));
        assertEquals("tls.keystore.password", AgentConfig.fileKey("HIVEKEEPER_TLS_KEYSTORE_PASSWORD"));
        assertEquals("cert.renew.window.days", AgentConfig.fileKey("HIVEKEEPER_CERT_RENEW_WINDOW_DAYS"));
    }

    /**
     * Every key the loader advertises as known must actually be read. A key listed in {@code KNOWN_KEYS} that
     * nothing reads is a lie told to the operator: the file looks accepted and silently does nothing.
     */
    @Test
    void everyKnownKeyIsAKeyTheConfigActuallyReads() {
        AgentConfig bare = AgentConfig.from(env(Map.of()), new Properties());
        for (String key : AgentConfig.KNOWN_KEYS) {
            AgentConfig withKey = AgentConfig.from(env(Map.of()), props(key, observableValueFor(key)));
            assertNotEquals(bare, withKey, "key '" + key + "' is declared known but never read");
        }
    }

    /** A value for {@code key} that no default would produce — otherwise "is it read?" cannot be observed. */
    private static String observableValueFor(String key) {
        return switch (key) {
            case "cert.renew.window.days", "cert.renew.check.hours", "shutdown.drain.seconds" -> "7";
            // Anything unrecognized maps to the TOFU default, so the sentinel has to be a real, non-default policy.
            case "ssh.hostkey" -> "strict";
            default -> "set-" + key;
        };
    }

    @Test
    void noConfigFileMeansAnEmptyOverlay() {
        // A null/blank path means "search the defaults"; none of them exist in a test working directory, and the
        // agent must then run on the environment alone rather than fail.
        assertTrue(AgentConfig.loadFile(null).isEmpty());
        assertTrue(AgentConfig.loadFile("  ").isEmpty());
    }

    @Test
    void anExplicitlyConfiguredPathThatIsMissingIsAHardError(@TempDir Path dir) {
        Path missing = dir.resolve("nope.conf");

        // Falling back to defaults here would start an agent pointing at localhost while the operator believes
        // it is pointing at their gateway.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AgentConfig.loadFile(missing.toString()));
        assertTrue(e.getMessage().contains(AgentConfig.CONFIG_PATH_VAR), e.getMessage());
        assertTrue(e.getMessage().contains("nope.conf"), e.getMessage());
    }

    @Test
    void loadsAnExplicitlyConfiguredFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("agent.conf");
        Files.writeString(file, """
                # HiveKeeper agent
                gateway.url = wss://hive.example.org:9443/agent
                agent.id = edge-02
                """);

        AgentConfig config = AgentConfig.from(env(Map.of()), AgentConfig.loadFile(file.toString()));

        assertEquals("wss://hive.example.org:9443/agent", config.gatewayUri().toString());
        assertEquals("edge-02", config.agentId());
    }

    @Test
    void anUnknownKeyIsIgnoredRatherThanFatal(@TempDir Path dir) throws Exception {
        // Typos are warned about (they are otherwise invisible), but a stale key must not stop the agent booting.
        Path file = dir.resolve("agent.conf");
        Files.writeString(file, """
                gateway.url = ws://gw/agent
                gateway.uri = a typo
                HIVEKEEPER_AGENT_ID = the env-var form, wrong in a file
                """);

        AgentConfig config = AgentConfig.from(env(Map.of()), AgentConfig.loadFile(file.toString()));

        assertEquals("ws://gw/agent", config.gatewayUri().toString());
        assertEquals("hive-agent", config.agentId());
    }
}
