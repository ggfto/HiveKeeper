package io.hivekeeper.agent;

import io.hivekeeper.core.crypto.SecretCipher;
import io.hivekeeper.core.model.BackupRemote;
import io.hivekeeper.core.spi.BackupDestinationStore;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Where the agent keeps the backup destination it was handed from the console. The sibling of
 * {@link WritableVaultCredentialProvider} and {@link FilePpskUserStore}: the cloud seals the token to this
 * agent, the engine unseals it locally, and it lands here encrypted at rest with AES-256-GCM
 * ({@link SecretCipher}, {@code gcm1:} tokens) under the agent's vault key.
 *
 * <p>A properties file with one destination — the whole agent pushes to one repository:
 *
 * <pre>
 *   url    = https://github.com/acme/hk-backups.git
 *   branch = main
 *   user   = hivekeeper
 *   token  = gcm1:Base64...      # encrypted at rest (vault key set)
 * </pre>
 *
 * <p>Reads are tolerant by design: a missing file simply means no destination yet, and a {@code gcm1:} token
 * that cannot be decrypted (the vault key changed, or was lost) is dropped with a warning rather than handed
 * to git as if it were a password — a push failing on a clearly-reported missing token beats one failing on
 * an authentication error nobody can explain.
 */
@Slf4j
final class FileBackupDestinationStore implements BackupDestinationStore {

    private final Path file;
    private final SecretCipher cipher;   // nullable — when null, the token is stored in the clear
    private BackupRemote current;
    private boolean warnedPlaintext;

    private FileBackupDestinationStore(Path file, SecretCipher cipher, BackupRemote current) {
        this.file = file;
        this.cipher = cipher;
        this.current = current;
    }

    static FileBackupDestinationStore fromFile(Path file, SecretCipher cipher) {
        return new FileBackupDestinationStore(file, cipher, load(file, cipher));
    }

    @Override
    public synchronized BackupRemote get() {
        return current;
    }

    @Override
    public synchronized void set(BackupRemote remote) {
        this.current = remote;
        persist();
        if (remote == null) {
            log.info("backup destination cleared; backups stay local");
        } else {
            log.info("backup destination set to {} (branch {})", remote.url(), remote.branch());
        }
    }

    private static BackupRemote load(Path file, SecretCipher cipher) {
        if (file == null || !Files.isReadable(file)) {
            return null;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            log.warn("could not read the backup destination from {}: {}", file, e.getMessage());
            return null;
        }
        String url = props.getProperty("url");
        if (url == null || url.isBlank()) {
            return null;
        }
        String stored = props.getProperty("token", "");
        String token = stored;
        if (SecretCipher.isEncrypted(stored)) {
            if (cipher == null) {
                log.warn("the backup destination token is encrypted but no vault key is configured; "
                        + "ignoring it — set HIVEKEEPER_VAULT_KEY or reconfigure the destination");
                return null;
            }
            try {
                token = cipher.decrypt(stored);
            } catch (RuntimeException e) {
                log.warn("could not decrypt the backup destination token (wrong vault key?); ignoring it");
                return null;
            }
        }
        return new BackupRemote(url, props.getProperty("user"), token, props.getProperty("branch"));
    }

    /** Atomic rewrite: a crash mid-write must not leave a half-written destination behind. */
    private void persist() {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Properties props = new Properties();
            if (current != null) {
                props.setProperty("url", current.url());
                props.setProperty("branch", current.branch());
                props.setProperty("user", current.username());
                props.setProperty("token", encode(current.token()));
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (var out = Files.newOutputStream(tmp)) {
                props.store(out, "HiveKeeper backup destination — managed from the console, do not edit");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("could not persist the backup destination to " + file, e);
        }
    }

    private String encode(String token) {
        if (token == null) {
            return "";
        }
        if (cipher == null) {
            if (!warnedPlaintext) {
                log.warn("no vault key configured — the backup destination token is stored in PLAINTEXT in {}. "
                        + "Set HIVEKEEPER_VAULT_KEY to encrypt it at rest.", file);
                warnedPlaintext = true;
            }
            return token;
        }
        return cipher.encrypt(token);
    }
}
