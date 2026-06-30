package io.hivekeeper.gateway.ppsk;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for admin-minted Private-PSK users (PPSK "Caminho B"). The gateway owns the key LIFECYCLE but
 * stores only metadata and a reference ({@code pskRef}) — the usable PSK lives on the on-prem agent's RADIUS
 * store, never here. Two implementations back this, selected by profile: {@link PostgresPpskUserService}
 * (shared-schema Postgres with row-level security) and {@link InMemoryPpskUserService} (the no-database
 * dev/demo stack). Both enforce that one organization can never read another's PPSK users; authorization is
 * layered on top in {@code GatewayController}.
 */
public interface PpskUserService {

    /** A PPSK user row. Carries NO usable key — {@code pskRef} is an opaque reference only. */
    record PpskUser(String id, String agentId, String securityObject, String userGroup, String username,
                    String pskRef, Integer userProfileAttr, Integer vlanId, String scheduleName,
                    List<String> macBindings, String status, Instant createdAt, Instant rotatedAt) {
    }

    /** Inserts a new active user and returns its id. */
    String create(String tenantId, String agentId, String securityObject, String userGroup, String username,
                  String pskRef, Integer userProfileAttr, Integer vlanId, String scheduleName,
                  List<String> macBindings);

    /** Records a rotation: a new {@code pskRef} and the rotation timestamp. */
    void markRotated(String tenantId, String id, String pskRef);

    /** Marks the user revoked (kept for audit; frees the username for re-creation). */
    void revoke(String tenantId, String id);

    /** All PPSK users (active and revoked) for an agent, newest first. */
    List<PpskUser> list(String tenantId, String agentId);

    /** A single user by id, within the tenant. */
    Optional<PpskUser> get(String tenantId, String id);
}
