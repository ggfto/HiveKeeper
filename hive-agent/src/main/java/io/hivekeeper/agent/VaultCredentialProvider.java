package io.hivekeeper.agent;

import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.spi.CredentialProvider;
import io.hivekeeper.core.spi.Credentials;
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
            // a credRef was given but not in the vault — fall through to the default rather than fail hard
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
        grouped.forEach((ref, fields) ->
                byRef.put(ref, new Credentials(fields.getOrDefault("user", ""), fields.getOrDefault("password", ""))));
        return new VaultCredentialProvider(byRef, fallback);
    }
}
