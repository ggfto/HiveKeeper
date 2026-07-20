package io.hivekeeper.agent;

import io.hivekeeper.core.transport.HostKeyPolicy;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * Agent configuration, resolved from two layers — an operator-owned config <b>file</b> and the
 * <b>environment</b> — so the same build serves a container (env) and a self-hosted service (file).
 *
 * <h2>Precedence</h2>
 * environment variable &gt; config file &gt; built-in default. Env wins so a container can override a baked-in
 * file without editing it; the file exists so an operator running the agent as a systemd/Windows service has one
 * editable place for the gateway URL and the enrollment token instead of a unit-file full of {@code Environment=}
 * lines.
 *
 * <h2>The file</h2>
 * A Java properties file. Its path comes from {@code HIVEKEEPER_AGENT_CONFIG}; when that is unset the agent looks
 * in {@link #DEFAULT_CONFIG_PATHS} and uses the first that exists. If none does, the agent runs on env alone —
 * so the container deployment is unchanged and needs no file. An explicitly configured path that does <i>not</i>
 * exist is a hard error, never a silent fallback: an operator who names a file means it.
 *
 * <h2>Keys</h2>
 * Every setting has exactly one env var and one file key, related by a mechanical rule: drop the
 * {@code HIVEKEEPER_} prefix, lowercase, and turn {@code _} into {@code .}
 * ({@code HIVEKEEPER_GATEWAY_URL} → {@code gateway.url}). Writing an env-style key <i>in the file</i> is rejected
 * with the name to use instead, and an unrecognized key is warned about — a typo in a self-hosted config file is
 * otherwise invisible until something silently does not work.
 *
 * <dl>
 *   <dt>{@code gateway.url} / {@code HIVEKEEPER_GATEWAY_URL}</dt>
 *   <dd>gateway WebSocket URL ({@code ws://} or, with mTLS, {@code wss://}). <b>No default that points anywhere
 *       real</b> — HiveKeeper is self-hosted and cannot guess your gateway; this is the one value every
 *       deployment must set.</dd>
 *   <dt>{@code agent.id} / {@code HIVEKEEPER_AGENT_ID}</dt><dd>stable agent identifier (defaults to the hostname)</dd>
 *   <dt>{@code default.user} / {@code default.password}</dt>
 *   <dd>fallback device credentials, used when a request carries no {@code credRef} or it is not in the vault</dd>
 *   <dt>{@code credential.vault}</dt>
 *   <dd>path to a per-device credential vault (properties: {@code <credRef>.user} / {@code <credRef>.password});
 *       resolved locally, never sent by the cloud. When set, HiveKeeper can also manage credentials from the UI
 *       (it writes this file).</dd>
 *   <dt>{@code vault.key}</dt>
 *   <dd>base64 AES-256 key that encrypts vault passwords (and PPSK keys) at rest; without it they are read and
 *       written in plaintext (a logged warning)</dd>
 *   <dt>{@code ppsk.store}</dt>
 *   <dd>path to the on-prem PPSK user store (properties); when set, and with mTLS configured for unsealing,
 *       HiveKeeper can mint/rotate/revoke Private PSKs from the UI. The store feeds the co-located RADIUS server.</dd>
 *   <dt>{@code radius.dir}</dt>
 *   <dd>directory the RADIUS provisioner writes its authorize file to (optional; PPSK keys are still stored
 *       without it, just not pushed to a RADIUS server)</dd>
 *   <dt>{@code backup.dir}</dt><dd>local git backup directory</dd>
 *   <dt>{@code tls.keystore} (+ {@code tls.keystore.password})</dt><dd>client keystore for mTLS (PKCS12)</dd>
 *   <dt>{@code tls.truststore} (+ {@code tls.truststore.password})</dt><dd>CA truststore (PKCS12)</dd>
 *   <dt>{@code ssh.hostkey}</dt>
 *   <dd>SSH host-key policy: {@code tofu} (default, trust-on-first-use), {@code strict} (keys must be
 *       pre-seeded), or {@code accept-all} (lab escape hatch, no verification)</dd>
 *   <dt>{@code known.hosts}</dt><dd>path to the managed known_hosts file (default {@code ./hivekeeper-known_hosts})</dd>
 *   <dt>{@code enrollment.token} / {@code enrollment.url}</dt>
 *   <dd>one-time token + gateway base URL for automated certificate bootstrap: when set and no keystore file
 *       exists yet, the agent generates a keypair, posts a CSR, and writes the issued keystore/truststore before
 *       connecting (see {@link EnrollmentBootstrap})</dd>
 *   <dt>{@code enrollment.cacert}</dt>
 *   <dd>optional PEM CA bundle to trust the gateway's HTTPS server cert during bootstrap (needed when the
 *       enrollment URL is {@code https://} and the CA is private)</dd>
 *   <dt>{@code cert.renew.window.days}</dt>
 *   <dd>auto-renew the mTLS cert once it is within this many days of expiry (default 30); renewal re-issues over
 *       the agent's CURRENT cert (mTLS) and needs {@code enrollment.url} set (see {@link EnrollmentRenewal})</dd>
 *   <dt>{@code cert.renew.check.hours}</dt><dd>how often to check the cert's expiry (default 12)</dd>
 *   <dt>{@code health.file}</dt>
 *   <dd>the file the agent touches while its gateway uplink is up, for a supervisor to watch (see
 *       {@link AgentHealth}); defaults into the temp directory, so health reporting works unconfigured</dd>
 * </dl>
 * mTLS is enabled when a keystore is configured (use a {@code wss://} gateway URL).
 */
@Slf4j
public record AgentConfig(URI gatewayUri, String agentId, String defaultUser, String defaultPassword,
                          String credentialVault, String vaultKey, String ppskStore, String radiusDir,
                          String backupDir, String backupDestinationStore,
                          String tlsKeystore, String tlsKeystorePassword,
                          String tlsTruststore, String tlsTruststorePassword,
                          HostKeyPolicy sshHostKeyPolicy, String knownHostsPath,
                          String enrollmentToken, String enrollmentUrl, String enrollmentCaCert,
                          int renewWindowDays, int renewCheckHours, String healthFile) {

    /** Env var naming the config file. Unset means "look in {@link #DEFAULT_CONFIG_PATHS}". */
    public static final String CONFIG_PATH_VAR = "HIVEKEEPER_AGENT_CONFIG";

    /** Searched in order when {@link #CONFIG_PATH_VAR} is unset; the first that exists is loaded. */
    static final List<String> DEFAULT_CONFIG_PATHS =
            List.of("agent.conf", "/etc/hivekeeper/agent.conf", "C:/ProgramData/HiveKeeper/agent.conf");

    /** Every file key the agent understands — anything else in the file is a typo worth warning about. */
    static final Set<String> KNOWN_KEYS = new TreeSet<>(Set.of(
            "gateway.url", "agent.id", "default.user", "default.password", "credential.vault", "vault.key",
            "ppsk.store", "radius.dir", "backup.dir", "backup.destination", "tls.keystore",
            "tls.keystore.password", "tls.truststore",
            "tls.truststore.password", "ssh.hostkey", "known.hosts", "enrollment.token", "enrollment.url",
            "enrollment.cacert", "cert.renew.window.days", "cert.renew.check.hours", "health.file"));

    public boolean mtlsEnabled() {
        return tlsKeystore != null && !tlsKeystore.isBlank();
    }

    /** True when an enrollment token + URL are configured, so the agent can bootstrap a cert if it lacks one. */
    public boolean enrollmentConfigured() {
        return enrollmentToken != null && !enrollmentToken.isBlank()
                && enrollmentUrl != null && !enrollmentUrl.isBlank();
    }

    /** True when the agent can auto-renew its cert: mTLS is on and an enrollment URL is configured (the renewal
     *  endpoint lives there). Unlike bootstrap, renewal needs no token — it authenticates with the current cert. */
    public boolean renewalEnabled() {
        return mtlsEnabled() && enrollmentUrl != null && !enrollmentUrl.isBlank();
    }

    /**
     * Loads the configuration: the config file (if any) overlaid by the environment. This is what the agent
     * process calls; {@link #from} is the pure core it delegates to.
     */
    public static AgentConfig load() {
        return from(System::getenv, loadFile(System.getenv(CONFIG_PATH_VAR)));
    }

    /**
     * Builds the configuration from an environment lookup and an already-parsed file. Pure and injectable, so the
     * tests pin precedence and parsing without touching the real environment or filesystem.
     *
     * @param env  environment lookup (env var name → value, or null)
     * @param file the config file's properties; empty when there is no file
     */
    static AgentConfig from(UnaryOperator<String> env, Properties file) {
        Lookup cfg = new Lookup(env, file);
        return new AgentConfig(
                URI.create(cfg.get("HIVEKEEPER_GATEWAY_URL", "ws://127.0.0.1:8090/agent")),
                cfg.get("HIVEKEEPER_AGENT_ID", defaultAgentId(env)),
                cfg.get("HIVEKEEPER_DEFAULT_USER", "admin"),
                cfg.get("HIVEKEEPER_DEFAULT_PASSWORD", ""),
                cfg.get("HIVEKEEPER_CREDENTIAL_VAULT", null),
                cfg.get("HIVEKEEPER_VAULT_KEY", null),
                cfg.get("HIVEKEEPER_PPSK_STORE", null),
                cfg.get("HIVEKEEPER_RADIUS_DIR", null),
                cfg.get("HIVEKEEPER_BACKUP_DIR", "hivekeeper-backups"),
                cfg.get("HIVEKEEPER_BACKUP_DESTINATION", "hivekeeper-backup-destination.properties"),
                cfg.get("HIVEKEEPER_TLS_KEYSTORE", null),
                cfg.get("HIVEKEEPER_TLS_KEYSTORE_PASSWORD", "changeit"),
                cfg.get("HIVEKEEPER_TLS_TRUSTSTORE", null),
                cfg.get("HIVEKEEPER_TLS_TRUSTSTORE_PASSWORD", "changeit"),
                hostKeyPolicy(cfg.get("HIVEKEEPER_SSH_HOSTKEY", "tofu")),
                cfg.get("HIVEKEEPER_KNOWN_HOSTS", "hivekeeper-known_hosts"),
                cfg.get("HIVEKEEPER_ENROLLMENT_TOKEN", null),
                cfg.get("HIVEKEEPER_ENROLLMENT_URL", null),
                cfg.get("HIVEKEEPER_ENROLLMENT_CACERT", null),
                cfg.getInt("HIVEKEEPER_CERT_RENEW_WINDOW_DAYS", 30),
                cfg.getInt("HIVEKEEPER_CERT_RENEW_CHECK_HOURS", 12),
                cfg.get("HIVEKEEPER_HEALTH_FILE", defaultHealthFile()));
    }

    /** A path that always exists and is always writable, so health reporting needs no configuration to work. */
    private static String defaultHealthFile() {
        return Path.of(System.getProperty("java.io.tmpdir"), "hivekeeper-agent.health").toString();
    }

    /**
     * Reads the config file, or returns empty properties when the agent is configured by environment alone.
     *
     * @param configuredPath the value of {@link #CONFIG_PATH_VAR}; null/blank means "search the defaults"
     * @throws IllegalStateException if {@code configuredPath} names a file that does not exist or cannot be read
     */
    static Properties loadFile(String configuredPath) {
        Path path;
        if (configuredPath != null && !configuredPath.isBlank()) {
            path = Path.of(configuredPath.trim());
            if (!Files.isRegularFile(path)) {
                // Fail loudly. Falling back to defaults here would start an agent that looks configured, points at
                // localhost, and never reaches the gateway the operator actually named.
                throw new IllegalStateException(
                        CONFIG_PATH_VAR + " points at '" + path + "', which is not a readable file");
            }
        } else {
            path = DEFAULT_CONFIG_PATHS.stream().map(Path::of).filter(Files::isRegularFile).findFirst().orElse(null);
            if (path == null) {
                log.info("no agent config file (searched {}); configuring from the environment alone",
                        DEFAULT_CONFIG_PATHS);
                return new Properties();
            }
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the agent config file '" + path + "': " + e.getMessage(), e);
        }
        log.info("loaded agent config from {} ({} setting(s); environment variables override it)",
                path.toAbsolutePath(), props.size());
        validateKeys(props);
        warnIfWorldReadable(path);
        return props;
    }

    /**
     * Warns about keys the agent does not understand. A misspelled key in a properties file is silently ignored
     * by {@link Properties}, so without this an operator's {@code gateway.uri=...} would leave the agent dialing
     * localhost with no hint why.
     */
    private static void validateKeys(Properties props) {
        List<String> unknown = new ArrayList<>();
        for (String key : props.stringPropertyNames()) {
            if (KNOWN_KEYS.contains(key)) {
                continue;
            }
            // The most likely mistake is pasting the env var name into the file. Name the key to use instead.
            if (key.toUpperCase(Locale.ROOT).startsWith("HIVEKEEPER_")) {
                log.warn("agent config: '{}' is an environment variable name, not a file key — use '{}' instead",
                        key, fileKey(key));
            } else {
                unknown.add(key);
            }
        }
        if (!unknown.isEmpty()) {
            log.warn("agent config: ignoring unknown key(s) {} — known keys are {}", unknown, KNOWN_KEYS);
        }
    }

    /**
     * Warns when the config file is readable by anyone. It holds the enrollment token, the vault key, and the
     * fallback SSH password — the whole point of moving them out of a unit file is that they end up somewhere
     * only the agent's user can read.
     */
    private static void warnIfWorldReadable(Path path) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
            if (perms.contains(PosixFilePermission.OTHERS_READ) || perms.contains(PosixFilePermission.GROUP_READ)) {
                log.warn("agent config {} is readable beyond its owner — it holds the enrollment token, the vault "
                        + "key and the fallback SSH password. Restrict it: chmod 600 {}", path, path);
            }
        } catch (IOException | UnsupportedOperationException e) {
            // Not a POSIX filesystem (Windows): nothing to check.
        }
    }

    /** The file key for an env var name: {@code HIVEKEEPER_GATEWAY_URL} → {@code gateway.url}. */
    static String fileKey(String envVar) {
        return envVar.toLowerCase(Locale.ROOT).replaceFirst("^hivekeeper_", "").replace('_', '.');
    }

    /** Environment-over-file lookup, keyed by the env var name (the file key is derived from it). */
    private record Lookup(UnaryOperator<String> env, Properties file) {

        String get(String envVar, String fallback) {
            String value = env.apply(envVar);
            if (value == null || value.isBlank()) {
                value = file.getProperty(fileKey(envVar));
            }
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        int getInt(String envVar, int fallback) {
            String value = get(envVar, null);
            if (value == null) {
                return fallback;
            }
            try {
                int parsed = Integer.parseInt(value);
                return parsed > 0 ? parsed : fallback;
            } catch (NumberFormatException e) {
                log.warn("agent config: {} ('{}') is not a positive integer — using {}", envVar, value, fallback);
                return fallback;
            }
        }
    }

    /** Map the SSH host-key setting to a {@link HostKeyPolicy} (unknown values fall back to TOFU). */
    private static HostKeyPolicy hostKeyPolicy(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "accept-all", "accept_all", "promiscuous" -> HostKeyPolicy.ACCEPT_ALL;
            case "strict", "known-hosts", "known_hosts" -> HostKeyPolicy.KNOWN_HOSTS;
            default -> HostKeyPolicy.TOFU;
        };
    }

    private static String defaultAgentId(UnaryOperator<String> env) {
        String host = env.apply("COMPUTERNAME");
        if (host == null || host.isBlank()) {
            host = env.apply("HOSTNAME");
        }
        return host == null || host.isBlank() ? "hive-agent" : host;
    }
}
