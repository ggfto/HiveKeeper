package io.hivekeeper.gateway.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Provides the {@link SecretCipher} used to encrypt persisted job secrets, only under the {@code postgres}
 * profile (the only profile that persists jobs). The key comes from {@code hivekeeper.crypto.key} —
 * Spring's relaxed binding maps the {@code HIVEKEEPER_CRYPTO_KEY} environment variable onto it.
 *
 * <p>There is deliberately NO silent dev-key fallback: the {@code postgres} profile is the same profile a
 * real deployment runs, so a forgotten key must fail the boot, not quietly encrypt real secrets under a
 * key that is checked into source. A no-setup local run can opt in with
 * {@code hivekeeper.crypto.allow-insecure-dev-key=true}; that flag must never be set in production.
 */
@Configuration
@Profile("postgres")
@Slf4j
public class CryptoConfig {

    // INSECURE, public key — usable ONLY behind the explicit allow-insecure-dev-key opt-in. Anyone with the
    // source can decrypt a DB written with it, so it is rejected by default even if configured directly.
    private static final String DEV_KEY = "Bpk8Ze+rY9xa8wpVcPu2BCNb8YQn20CAvknhxShG/wM=";

    @Bean
    public SecretCipher secretCipher(
            @Value("${hivekeeper.crypto.key:}") String configuredKey,
            @Value("${hivekeeper.crypto.allow-insecure-dev-key:false}") boolean allowDevKey) {

        if (configuredKey == null || configuredKey.isBlank()) {
            if (allowDevKey) {
                log.warn("Using the built-in INSECURE dev key for at-rest job encryption "
                        + "(hivekeeper.crypto.allow-insecure-dev-key=true). NEVER enable this in production.");
                return SecretCipher.fromBase64(DEV_KEY);
            }
            throw new IllegalStateException("HIVEKEEPER_CRYPTO_KEY is required under the postgres profile "
                    + "(base64 of 32 random bytes). Refusing to start with no key. For local-only use set "
                    + "hivekeeper.crypto.allow-insecure-dev-key=true.");
        }
        if (DEV_KEY.equals(configuredKey.trim()) && !allowDevKey) {
            throw new IllegalStateException("Refusing to start: hivekeeper.crypto.key is the public built-in "
                    + "dev key. Generate a private key, or set hivekeeper.crypto.allow-insecure-dev-key=true "
                    + "for local-only use.");
        }
        return SecretCipher.fromBase64(configuredKey);
    }
}
