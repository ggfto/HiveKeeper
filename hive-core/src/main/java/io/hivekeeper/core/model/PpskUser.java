package io.hivekeeper.core.model;

/**
 * A Private PSK user. Stored in the AP's TPM and exported via a separate CLI channel
 * ({@code show running-config users password}). Modeled as a distinct type from config because the
 * backup/restore path for users is independent and lossy. Not parsed in v0.1 (captured as raw text).
 */
public record PpskUser(String userName, String ssid, String macBinding) {
}
