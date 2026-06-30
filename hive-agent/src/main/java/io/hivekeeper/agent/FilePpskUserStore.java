package io.hivekeeper.agent;

import io.hivekeeper.agent.radius.RadiusProvisioner;
import io.hivekeeper.core.crypto.SecretCipher;
import io.hivekeeper.core.model.PpskUserRecord;
import io.hivekeeper.core.spi.PpskUserStore;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

/**
 * The on-prem store of Private-PSK users (PPSK "Caminho B"), the PSK analogue of
 * {@link WritableVaultCredentialProvider}. The cloud generates a key, seals it to this agent, and sends a
 * {@code ManagePpskUser} command; the engine unseals it locally and writes it here. The PSK is encrypted at
 * rest with AES-256-GCM ({@link SecretCipher}, {@code gcm1:} tokens) when a vault key is configured; the
 * cloud never holds the usable key.
 *
 * <p>Backed by a properties file. Records are stored under a numeric index (so a security-object name with a
 * dot cannot collide with a field separator), and re-keyed in memory by {@link PpskUserRecord#key()}:
 *
 * <pre>
 *   0.so = Corp
 *   0.group = staff
 *   0.user = alice
 *   0.psk = gcm1:Base64...      # encrypted at rest (vault key set)
 *   0.attr = 99                 # optional user-profile attribute
 *   0.vlan = 30                 # optional VLAN id
 *   0.schedule = biz-hours      # optional validity schedule
 *   0.macs = aa:bb:cc:dd:ee:ff  # optional, comma-separated MAC bindings
 *   0.status = active
 * </pre>
 *
 * <p>An optional {@link io.hivekeeper.agent.radius.RadiusProvisioner} is invoked after every mutation so the
 * co-located RADIUS server reflects the current user set.
 */
@Slf4j
final class FilePpskUserStore implements PpskUserStore {

    private final Map<String, PpskUserRecord> byKey;
    private final Path file;
    private final SecretCipher cipher;      // nullable — when null, PSKs are stored in the clear
    private final RadiusProvisioner provisioner;   // nullable — no RADIUS reprovision when null
    private boolean warnedPlaintext;

    private FilePpskUserStore(Map<String, PpskUserRecord> byKey, Path file, SecretCipher cipher,
                              RadiusProvisioner provisioner) {
        this.byKey = new LinkedHashMap<>(byKey);
        this.file = file;
        this.cipher = cipher;
        this.provisioner = provisioner;
    }

    @Override
    public synchronized void put(PpskUserRecord record) {
        byKey.put(record.key(), record);
        persist();
        reprovision();
        log.info("stored PPSK user '{}' on security-object '{}' ({} at rest)", record.username(),
                record.securityObject(), cipher != null ? "encrypted" : "PLAINTEXT");
    }

    @Override
    public synchronized boolean remove(String securityObject, String username) {
        boolean existed = byKey.remove(PpskUserRecord.key(securityObject, username)) != null;
        if (existed) {
            persist();
            reprovision();
            log.info("revoked PPSK user '{}' on security-object '{}'", username, securityObject);
        }
        return existed;
    }

    @Override
    public synchronized List<PpskUserRecord> list() {
        return new ArrayList<>(byKey.values());
    }

    private void reprovision() {
        if (provisioner != null) {
            provisioner.provision(new ArrayList<>(byKey.values()));
        }
    }

    /** Atomically rewrites the store file from the in-memory map. */
    private void persist() {
        if (cipher == null && !warnedPlaintext) {
            log.warn("HIVEKEEPER_VAULT_KEY is not set — PPSK keys are written to {} in PLAINTEXT. "
                    + "Set a base64 AES-256 key to encrypt the PPSK store at rest.", file);
            warnedPlaintext = true;
        }
        Properties props = new Properties();
        int i = 0;
        for (PpskUserRecord r : byKey.values()) {
            String p = i++ + ".";
            props.setProperty(p + "so", r.securityObject());
            if (r.userGroup() != null) {
                props.setProperty(p + "group", r.userGroup());
            }
            props.setProperty(p + "user", r.username());
            String psk = r.psk() == null ? "" : r.psk();
            props.setProperty(p + "psk", cipher != null ? cipher.encrypt(psk) : psk);
            if (r.userProfileAttr() != null) {
                props.setProperty(p + "attr", String.valueOf(r.userProfileAttr()));
            }
            if (r.vlanId() != null) {
                props.setProperty(p + "vlan", String.valueOf(r.vlanId()));
            }
            if (r.scheduleName() != null) {
                props.setProperty(p + "schedule", r.scheduleName());
            }
            if (!r.macBindings().isEmpty()) {
                props.setProperty(p + "macs", String.join(",", r.macBindings()));
            }
            props.setProperty(p + "status", r.status() == null ? "active" : r.status());
        }
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(tmp)) {
                props.store(out, "HiveKeeper PPSK user store — managed; do not edit while the agent runs");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist the PPSK store to " + file, e);
        }
    }

    /**
     * Loads (or starts) a store. A missing file is not an error — it starts empty. Encrypted ({@code gcm1:})
     * PSKs are decrypted with {@code cipher}; an encrypted entry with no key configured is skipped (it would
     * otherwise resolve to ciphertext). After loading, the RADIUS server is reprovisioned from the result.
     */
    static FilePpskUserStore fromFile(Path file, SecretCipher cipher, RadiusProvisioner provisioner)
            throws IOException {
        Map<String, PpskUserRecord> byKey = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            log.info("PPSK store {} does not exist yet; starting empty", file);
            FilePpskUserStore store = new FilePpskUserStore(byKey, file, cipher, provisioner);
            store.reprovision();
            return store;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        // Group by numeric index prefix (the part before the first dot).
        Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
        for (String index : new TreeSet<>(indices(props))) {
            grouped.put(index, new LinkedHashMap<>());
        }
        for (String key : props.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot <= 0) {
                continue;
            }
            grouped.computeIfAbsent(key.substring(0, dot), k -> new LinkedHashMap<>())
                    .put(key.substring(dot + 1), props.getProperty(key));
        }
        int skipped = 0;
        for (var entry : grouped.values()) {
            String so = entry.get("so");
            String user = entry.get("user");
            if (so == null || so.isBlank() || user == null || user.isBlank()) {
                skipped++;
                continue;
            }
            String rawPsk = entry.getOrDefault("psk", "");
            String psk;
            if (SecretCipher.isEncrypted(rawPsk)) {
                if (cipher == null) {
                    log.warn("PPSK entry '{}/{}' is encrypted but HIVEKEEPER_VAULT_KEY is not set; skipping it",
                            so, user);
                    skipped++;
                    continue;
                }
                psk = cipher.decrypt(rawPsk);
            } else {
                psk = rawPsk;
            }
            PpskUserRecord record = new PpskUserRecord(so, entry.get("group"), user, psk,
                    parseInt(entry.get("attr")), parseInt(entry.get("vlan")), entry.get("schedule"),
                    parseMacs(entry.get("macs")), entry.getOrDefault("status", "active"));
            byKey.put(record.key(), record);
        }
        if (skipped > 0) {
            log.warn("loaded PPSK store from {} with {} entr{} skipped", file, skipped, skipped == 1 ? "y" : "ies");
        }
        FilePpskUserStore store = new FilePpskUserStore(byKey, file, cipher, provisioner);
        store.reprovision();
        return store;
    }

    private static TreeSet<String> indices(Properties props) {
        TreeSet<String> out = new TreeSet<>();
        for (String key : props.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot > 0) {
                out.add(key.substring(0, dot));
            }
        }
        return out;
    }

    private static Integer parseInt(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> parseMacs(String v) {
        if (v == null || v.isBlank()) {
            return List.of();
        }
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public String toString() {
        return "FilePpskUserStore[file=" + file + ", users=" + byKey.size()
                + ", atRest=" + (cipher != null ? "encrypted" : "plaintext") + "]";
    }
}
