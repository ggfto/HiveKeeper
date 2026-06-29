package io.hivekeeper.agent;

import io.hivekeeper.core.crypto.SecretCipher;
import io.hivekeeper.core.model.DeviceRef;
import io.hivekeeper.core.spi.Credentials;
import io.hivekeeper.core.spi.WritableCredentialProvider;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Per-device credential resolution on the agent, now writable so HiveKeeper can manage credentials from the
 * UI instead of a hand-edited file. The cloud sends only the {@code credRef} on the {@link DeviceRef}; the
 * secret lives here, on the agent, and is resolved locally — never held by the control plane. A request with
 * no {@code credRef} (or an unknown one) falls back to a configured default.
 *
 * <p>Backed by a properties file. The username is stored in the clear; the password is encrypted at rest with
 * AES-256-GCM ({@link SecretCipher}, {@code gcm1:} tokens) when a vault key is configured:
 *
 * <pre>
 *   lab-ap.user = admin
 *   lab-ap.password = gcm1:Base64...           # encrypted at rest (vault key set)
 *   branch-2.user = admin
 *   branch-2.password = aerohive               # legacy plaintext (no vault key) — still read
 * </pre>
 *
 * <p>Reads transparently handle both forms. Writes encrypt when a vault key is present and WARN once when it
 * is not (so an operator who manages credentials without a key knows the on-disk secret is plaintext).
 */
@Slf4j
final class WritableVaultCredentialProvider implements WritableCredentialProvider {

    private final Map<String, Credentials> byRef;
    private final Credentials fallback;     // nullable
    private final Path file;
    private final SecretCipher cipher;      // nullable — when null, passwords are stored in the clear
    private boolean warnedPlaintext;

    private WritableVaultCredentialProvider(Map<String, Credentials> byRef, Credentials fallback, Path file,
                                            SecretCipher cipher) {
        this.byRef = new LinkedHashMap<>(byRef);
        this.fallback = fallback;
        this.file = file;
        this.cipher = cipher;
    }

    @Override
    public synchronized Optional<Credentials> resolve(DeviceRef device) {
        if (device.credRef() != null) {
            Credentials c = byRef.get(device.credRef());
            if (c != null) {
                return Optional.of(c);
            }
            // Explicitly-requested credRef not in the vault (typo, or not yet set): fall back rather than fail
            // hard, but WARN — an otherwise-silent credential misconfiguration. No secret is logged.
            log.warn("credRef '{}' for host {} not found in the vault; falling back to the default credential",
                    device.credRef(), device.host());
        }
        return Optional.ofNullable(fallback);
    }

    @Override
    public synchronized void store(String credRef, Credentials credentials) {
        if (credRef == null || credRef.isBlank()) {
            throw new IllegalArgumentException("credRef is required to store a credential");
        }
        byRef.put(credRef, credentials);
        persist();
        log.info("stored credential for credRef '{}' ({} at rest)", credRef,
                cipher != null ? "encrypted" : "PLAINTEXT");
    }

    /** Atomically rewrites the vault file from the in-memory map. */
    private void persist() {
        Properties props = new Properties();
        for (var e : byRef.entrySet()) {
            props.setProperty(e.getKey() + ".user", e.getValue().username());
            String pw = e.getValue().password() == null ? "" : e.getValue().password();
            props.setProperty(e.getKey() + ".password", cipher != null ? cipher.encrypt(pw) : pw);
        }
        if (cipher == null && !warnedPlaintext) {
            log.warn("HIVEKEEPER_VAULT_KEY is not set — credential passwords are written to {} in PLAINTEXT. "
                    + "Set a base64 AES-256 key to encrypt the vault at rest.", file);
            warnedPlaintext = true;
        }
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(tmp)) {
                props.store(out, "HiveKeeper credential vault — managed; do not edit while the agent runs");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist the credential vault to " + file, e);
        }
    }

    /**
     * Loads (or starts) a vault. A missing file is not an error — the vault may be populated later from the
     * UI; it starts empty. Encrypted ({@code gcm1:}) passwords are decrypted with {@code cipher}; if a value
     * is encrypted but no key is configured, that entry is skipped (it would otherwise resolve to ciphertext).
     */
    static WritableVaultCredentialProvider fromFile(Path file, Credentials fallback, SecretCipher cipher)
            throws IOException {
        Map<String, Credentials> byRef = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            log.info("credential vault {} does not exist yet; starting empty", file);
            return new WritableVaultCredentialProvider(byRef, fallback, file, cipher);
        }
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
        int skipped = 0;
        for (var entry : grouped.entrySet()) {
            String user = entry.getValue().get("user");
            if (user == null || user.isBlank()) {
                log.warn("vault entry '{}' has no '.user' line; skipping it (its credRef will fall back to the "
                        + "default credential)", entry.getKey());
                skipped++;
                continue;
            }
            String rawPassword = entry.getValue().getOrDefault("password", "");
            String password;
            if (SecretCipher.isEncrypted(rawPassword)) {
                if (cipher == null) {
                    log.warn("vault entry '{}' is encrypted but HIVEKEEPER_VAULT_KEY is not set; skipping it",
                            entry.getKey());
                    skipped++;
                    continue;
                }
                password = cipher.decrypt(rawPassword);
            } else {
                password = rawPassword;     // legacy plaintext entry
            }
            byRef.put(entry.getKey(), new Credentials(user, password));
        }
        if (skipped > 0) {
            log.warn("loaded vault from {} with {} entr{} skipped", file, skipped, skipped == 1 ? "y" : "ies");
        }
        return new WritableVaultCredentialProvider(byRef, fallback, file, cipher);
    }

    /** Bytes-on-disk view for tests/diagnostics: the raw stored password token for a credRef (encrypted or not). */
    synchronized String storedPasswordToken(String credRef) {
        Credentials c = byRef.get(credRef);
        if (c == null) {
            return null;
        }
        return cipher != null ? "(encrypted)" : c.password();
    }

    @Override
    public String toString() {
        return "WritableVaultCredentialProvider[file=" + file + ", entries=" + byRef.size()
                + ", atRest=" + (cipher != null ? "encrypted" : "plaintext") + "]";
    }
}
