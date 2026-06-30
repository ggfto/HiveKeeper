package io.hivekeeper.gateway.ppsk;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The dev/demo PPSK-user store: a process-local, tenant-scoped map store that lets the {@code !postgres}
 * stack run the PPSK-user UI without a database. Mirrors {@link PostgresPpskUserService}'s behaviour
 * (per-tenant isolation, one active user per agent/security-object/username, revoked rows kept for audit) but
 * holds nothing across a restart. Synchronized because mutations do compound read-modify-write.
 */
@Service
@Profile("!postgres")
public class InMemoryPpskUserService implements PpskUserService {

    private final Map<String, Map<String, PpskUser>> byTenant = new LinkedHashMap<>();

    private synchronized Map<String, PpskUser> tenant(String tenantId) {
        return byTenant.computeIfAbsent(tenantId, k -> new LinkedHashMap<>());
    }

    @Override
    public synchronized String create(String tenantId, String agentId, String securityObject, String userGroup,
                                      String username, String pskRef, Integer userProfileAttr, Integer vlanId,
                                      String scheduleName, List<String> macBindings) {
        boolean clash = tenant(tenantId).values().stream().anyMatch(u -> "active".equals(u.status())
                && u.agentId().equals(agentId) && u.securityObject().equals(securityObject)
                && u.username().equals(username));
        if (clash) {
            throw new IllegalStateException("an active PPSK user '" + username + "' already exists on '"
                    + securityObject + "'");
        }
        String id = "ppsk-" + UUID.randomUUID();
        tenant(tenantId).put(id, new PpskUser(id, agentId, securityObject, userGroup, username, pskRef,
                userProfileAttr, vlanId, scheduleName, macBindings == null ? List.of() : List.copyOf(macBindings),
                "active", Instant.now(), null));
        return id;
    }

    @Override
    public synchronized void markRotated(String tenantId, String id, String pskRef) {
        PpskUser u = tenant(tenantId).get(id);
        if (u != null) {
            tenant(tenantId).put(id, new PpskUser(u.id(), u.agentId(), u.securityObject(), u.userGroup(),
                    u.username(), pskRef, u.userProfileAttr(), u.vlanId(), u.scheduleName(), u.macBindings(),
                    u.status(), u.createdAt(), Instant.now()));
        }
    }

    @Override
    public synchronized void revoke(String tenantId, String id) {
        PpskUser u = tenant(tenantId).get(id);
        if (u != null) {
            tenant(tenantId).put(id, new PpskUser(u.id(), u.agentId(), u.securityObject(), u.userGroup(),
                    u.username(), u.pskRef(), u.userProfileAttr(), u.vlanId(), u.scheduleName(), u.macBindings(),
                    "revoked", u.createdAt(), u.rotatedAt()));
        }
    }

    @Override
    public synchronized List<PpskUser> list(String tenantId, String agentId) {
        List<PpskUser> out = new ArrayList<>(tenant(tenantId).values().stream()
                .filter(u -> u.agentId().equals(agentId)).toList());
        out.sort(Comparator.comparing(PpskUser::createdAt).reversed());
        return out;
    }

    @Override
    public synchronized Optional<PpskUser> get(String tenantId, String id) {
        return Optional.ofNullable(tenant(tenantId).get(id));
    }
}
