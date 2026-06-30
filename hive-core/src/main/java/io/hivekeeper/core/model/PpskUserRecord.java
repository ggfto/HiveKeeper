package io.hivekeeper.core.model;

import java.util.List;

/**
 * A provisioned Private-PSK user as held by the on-prem RADIUS store (PPSK "Caminho B"). This is the
 * on-prem contract: it carries the plaintext {@code psk} because the key only ever exists on the LAN
 * (generated in the cloud, sealed to the agent, unsealed locally, then held here encrypted at rest) — the
 * cloud control plane stores only a reference and metadata, never this record. Distinct from
 * {@link PpskUser}, which models the AP's own TPM-backed user list captured by a backup.
 *
 * <p>{@code userProfileAttr} / {@code vlanId} are the policy the RADIUS server returns on Access-Accept;
 * {@code macBindings} are optional bound client MACs; {@code scheduleName} an optional validity window.
 */
public record PpskUserRecord(String securityObject, String userGroup, String username, String psk,
                             Integer userProfileAttr, Integer vlanId, String scheduleName,
                             List<String> macBindings, String status) {

    public PpskUserRecord {
        if (securityObject == null || securityObject.isBlank()) {
            throw new IllegalArgumentException("securityObject required");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username required");
        }
        macBindings = macBindings == null ? List.of() : List.copyOf(macBindings);
    }

    /** Stable store key (a security object scopes its own user namespace). */
    public String key() {
        return key(securityObject, username);
    }

    /** The store key for a security-object + username pair (so callers need not build a record to look one up). */
    public static String key(String securityObject, String username) {
        return securityObject + "/" + username;
    }

    @Override
    public String toString() {
        return "PpskUserRecord[so=" + securityObject + ", user=" + username + ", psk=***, status=" + status + "]";
    }
}
