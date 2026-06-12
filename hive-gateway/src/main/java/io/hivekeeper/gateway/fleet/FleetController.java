package io.hivekeeper.gateway.fleet;

import io.hivekeeper.gateway.access.AccessService;
import io.hivekeeper.gateway.access.ResourceScope;
import io.hivekeeper.gateway.access.Role;
import io.hivekeeper.gateway.tenant.Tenant;
import io.hivekeeper.gateway.tenant.TenantStore;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Optional;

/**
 * The organization's fleet + access API, scoped by tenant via {@code X-Tenant-Key}. In this phase the API
 * key is a service principal acting as organization owner, so these endpoints are reachable with a valid
 * key; per-user enforcement (the {@link AccessService} resolver applied to the caller's identity) arrives
 * with OIDC login. The {@code /api/access/effective} endpoint already exercises the resolver against any
 * seeded user, so the scoped-role logic is verifiable today. Only present under the {@code postgres} profile.
 */
@RestController
@Profile("postgres")
public class FleetController {

    public record CreateSite(String name) {
    }

    public record CreateGroup(String name, String siteId) {
    }

    public record RegisterDevice(String serial, String model, String label, String mgmtIp,
                                 String siteId, String agentId) {
    }

    public record TagRequest(String groupId) {
    }

    public record IdResponse(String id) {
    }

    public record EffectiveResponse(String userId, String scopeKind, String scopeId, String role) {
    }

    public record ApiError(String error, String detail) {
    }

    private final TenantStore tenants;
    private final FleetService fleet;
    private final AccessService access;

    public FleetController(TenantStore tenants, FleetService fleet, AccessService access) {
        this.tenants = tenants;
        this.fleet = fleet;
        this.access = access;
    }

    @GetMapping("/api/sites")
    public ResponseEntity<?> listSites(@RequestHeader(value = "X-Tenant-Key", required = false) String apiKey) {
        return withTenant(apiKey, tenant -> ResponseEntity.ok(fleet.listSites(tenant)));
    }

    @PostMapping("/api/sites")
    public ResponseEntity<?> createSite(@RequestHeader(value = "X-Tenant-Key", required = false) String apiKey,
                                        @RequestBody CreateSite req) {
        return withTenant(apiKey, tenant -> {
            if (isBlank(req.name())) {
                return badRequest("name is required");
            }
            return ResponseEntity.ok(new IdResponse(fleet.createSite(tenant, req.name().trim())));
        });
    }

    @GetMapping("/api/groups")
    public ResponseEntity<?> listGroups(@RequestHeader(value = "X-Tenant-Key", required = false) String apiKey) {
        return withTenant(apiKey, tenant -> ResponseEntity.ok(fleet.listGroups(tenant)));
    }

    @PostMapping("/api/groups")
    public ResponseEntity<?> createGroup(@RequestHeader(value = "X-Tenant-Key", required = false) String apiKey,
                                         @RequestBody CreateGroup req) {
        return withTenant(apiKey, tenant -> {
            if (isBlank(req.name())) {
                return badRequest("name is required");
            }
            // siteId optional: null => a cross-site tag group.
            String siteId = isBlank(req.siteId()) ? null : req.siteId().trim();
            return ResponseEntity.ok(new IdResponse(fleet.createGroup(tenant, req.name().trim(), siteId)));
        });
    }

    @GetMapping("/api/devices")
    public ResponseEntity<?> listDevices(@RequestHeader(value = "X-Tenant-Key", required = false) String apiKey) {
        return withTenant(apiKey, tenant -> ResponseEntity.ok(fleet.listDevices(tenant)));
    }

    @PostMapping("/api/devices")
    public ResponseEntity<?> registerDevice(@RequestHeader(value = "X-Tenant-Key", required = false) String apiKey,
                                            @RequestBody RegisterDevice req) {
        return withTenant(apiKey, tenant -> {
            if (isBlank(req.serial())) {
                return badRequest("serial is required (it is the device's stable identity)");
            }
            String id = fleet.registerDevice(tenant, req.serial().trim(), req.model(), req.label(),
                    req.mgmtIp(), isBlank(req.siteId()) ? null : req.siteId().trim(),
                    isBlank(req.agentId()) ? null : req.agentId().trim());
            return ResponseEntity.ok(new IdResponse(id));
        });
    }

    @PostMapping("/api/devices/{deviceId}/groups")
    public ResponseEntity<?> tagDevice(@RequestHeader(value = "X-Tenant-Key", required = false) String apiKey,
                                       @PathVariable String deviceId, @RequestBody TagRequest req) {
        return withTenant(apiKey, tenant -> {
            if (isBlank(req.groupId())) {
                return badRequest("groupId is required");
            }
            fleet.tagDevice(tenant, deviceId, req.groupId().trim());
            return ResponseEntity.ok().build();
        });
    }

    /**
     * Resolves a user's effective role on a resource, demonstrating the scoped-role engine over real data.
     * Provide exactly one of deviceId / groupId / siteId, or none for the organization scope.
     */
    @GetMapping("/api/access/effective")
    public ResponseEntity<?> effective(@RequestHeader(value = "X-Tenant-Key", required = false) String apiKey,
                                       @RequestParam String userId,
                                       @RequestParam(required = false) String siteId,
                                       @RequestParam(required = false) String groupId,
                                       @RequestParam(required = false) String deviceId) {
        return withTenant(apiKey, tenant -> {
            ResourceScope scope;
            String kind;
            String id;
            if (!isBlank(deviceId)) {
                Optional<ResourceScope> deviceScope = fleet.deviceScope(tenant, deviceId.trim());
                if (deviceScope.isEmpty()) {
                    return status404("device_not_found", deviceId);
                }
                scope = deviceScope.get();
                kind = "device";
                id = deviceId.trim();
            } else if (!isBlank(groupId)) {
                // Derive the group's site from the DB (not the caller) so a SITE grant covers a site-pinned
                // group correctly.
                Optional<ResourceScope> groupScope = fleet.groupScope(tenant, groupId.trim());
                if (groupScope.isEmpty()) {
                    return status404("group_not_found", groupId);
                }
                scope = groupScope.get();
                kind = "group";
                id = groupId.trim();
            } else if (!isBlank(siteId)) {
                scope = ResourceScope.site(siteId.trim());
                kind = "site";
                id = siteId.trim();
            } else {
                scope = ResourceScope.org();
                kind = "org";
                id = tenant;
            }
            String role = access.effectiveRole(tenant, userId, scope).map(Role::name).orElse(null);
            return ResponseEntity.ok(new EffectiveResponse(userId, kind, id, role));
        });
    }

    // -- helpers ----------------------------------------------------------------

    private interface TenantAction {
        ResponseEntity<?> run(String tenantId);
    }

    private ResponseEntity<?> withTenant(String apiKey, TenantAction action) {
        Optional<Tenant> tenant = tenants.tenantByApiKey(apiKey);
        if (tenant.isEmpty()) {
            return ResponseEntity.status(401).body(new ApiError("unauthorized", "missing or invalid X-Tenant-Key"));
        }
        return action.run(tenant.get().tenantId());
    }

    private static ResponseEntity<?> badRequest(String detail) {
        return ResponseEntity.badRequest().body(new ApiError("bad_request", detail));
    }

    private static ResponseEntity<?> status404(String error, String detail) {
        return ResponseEntity.status(404).body(new ApiError(error, detail));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
