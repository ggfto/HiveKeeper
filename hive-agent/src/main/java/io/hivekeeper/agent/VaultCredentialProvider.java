package io.hivekeeper.agent;

import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.spi.Credentials;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Per-device credential resolution on the agent: a local vault maps each {@code credRef} to a username and
 * password. The cloud sends only the {@code credRef} on the {@link DeviceRef}; the secret lives here, on the
 * agent, and is resolved locally — never held by the control plane. A request with no {@code credRef} (or an
 * unknown one) falls back to a configured default. Backed by a properties file:
 *
 * <pre>
 *   lab-ap.user = admin
 *   lab-ap.password = aerohive
 *   branch-2.user = admin
 *   branch-2.password = ...
 * </pre>
 */
@Slf4j
final class VaultCredentialProvider implements CredentialProvider {

    private final Map<String, Credentials> byRef;
    private final Credentials fallback;   // nullable

    VaultCredentialProvider(Map<String, Credentials> byRef, Credentials fallback) {
        this.byRef = Map.copyOf(byRef);
        this.fallback = fallback;
    }

    @Override
    public Optional<Credentials> resolve(DeviceRef device) {
        if (device.credRef() != null) {
            Credentials c = byRef.get(device.credRef());
            if (c != null) {
                return Optional.of(c);
            }
            // A credRef was explicitly requested but is not in the vault (typo, or the vault was not updated
            // when the device was adopted). Fall back to the default rather than fail hard, but WARN — this is
            // a credential misconfiguration that would otherwise be silent. Credentials.toString masks the
            // password, but we log no secret here regardless.
            log.warn("credRef '{}' for host {} not found in the vault; falling back to the default credential",
                    device.credRef(), device.host());
        }
        return Optional.ofNullable(fallback);
    }

    /** Loads a {@code <ref>.user} / {@code <ref>.password} properties vault. */
    static VaultCredentialProvider fromFile(Path file, Credentials fallback) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            int dot = key.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            grouped.computeIfAbsent(key.substring(0, dot), k -> new HashMap<>())
                    .put(key.substring(dot + 1), props.getProperty(key));
        }
        Map<String, Credentials> byRef = new LinkedHashMap<>();
        int skipped = 0;
        for (var entry : grouped.entrySet()) {
            String user = entry.getValue().get("user");
            if (user == null || user.isBlank()) {
                // One malformed entry (a stray dotted key, or a '.password' with no '.user') must NOT abort the
                // whole load and take the agent down — skip it and let that credRef fall back to the default.
                log.warn("vault entry '{}' has no '.user' line; skipping it (its credRef will fall back to the "
                        + "default credential)", entry.getKey());
                skipped++;
                continue;
            }
            byRef.put(entry.getKey(), new Credentials(user, entry.getValue().getOrDefault("password", "")));
        }
        if (skipped > 0) {
            log.warn("loaded vault from {} with {} malformed entr{} skipped", file, skipped,
                    skipped == 1 ? "y" : "ies");
        }
        return new VaultCredentialProvider(byRef, fallback);
    }
}
