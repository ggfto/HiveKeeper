package io.hivekeeper.gateway.fleet;

import io.hivekeeper.gateway.access.AccessGuard;
import io.hivekeeper.gateway.access.AccessService;
import io.hivekeeper.gateway.access.Principal;
import io.hivekeeper.gateway.access.ResourceScope;
import io.hivekeeper.gateway.access.Role;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Optional;

/**
 * The organization's fleet + access API. Every endpoint resolves the caller via {@link AccessGuard} (a
 * user's bearer JWT + active org, or the {@code X-Tenant-Key} service principal) and requires a scoped role:
 * reads need {@code viewer}, structural changes need {@code admin}. Cross-tenant writes are additionally
 * impossible at the database (composite FKs). Only present under the {@code postgres} profile.
 */
@RestController
@Profile("postgres")
public class FleetController {

    public record CreateSite(String name) {
    }

    public record CreateGroup(String name, String siteId) {
    }

    public record RegisterDevice(String serial, String model, String label, String mgmtIp,
                                 String siteId, String agentId, String credRef) {
    }

    public record TagRequest(String groupId) {
    }

    public record UpdateDevice(String label, String siteId) {
    }

    public record IdResponse(String id) {
    }

    public record EffectiveResponse(String userId, String scopeKind, String scopeId, String role) {
    }

    public record ApiError(String error, String detail) {
    }

    private final AccessGuard guard;
    private final FleetService fleet;
    private final AccessService access;

    public FleetController(AccessGuard guard, FleetService fleet, AccessService access) {
        this.guard = guard;
        this.fleet = fleet;
        this.access = access;
    }

    @GetMapping("/api/sites")
    public ResponseEntity<?> listSites() {
        Principal p = guard.authenticate();
        guard.require(p, Role.VIEWER, ResourceScope.org());
        return ResponseEntity.ok(fleet.listSites(p.tenantId()));
    }

    @PostMapping("/api/sites")
    public ResponseEntity<?> createSite(@RequestBody CreateSite req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.ADMIN, ResourceScope.org());
        if (isBlank(req.name())) {
            return badRequest("name is required");
        }
        return ResponseEntity.ok(new IdResponse(fleet.createSite(p.tenantId(), req.name().trim())));
    }

    @GetMapping("/api/groups")
    public ResponseEntity<?> listGroups() {
        Principal p = guard.authenticate();
        guard.require(p, Role.VIEWER, ResourceScope.org());
        return ResponseEntity.ok(fleet.listGroups(p.tenantId()));
    }

    @PostMapping("/api/groups")
    public ResponseEntity<?> createGroup(@RequestBody CreateGroup req) {
        Principal p = guard.authenticate();
        if (isBlank(req.name())) {
            return badRequest("name is required");
        }
        // A site-pinned group is admin-on-that-site; a cross-site tag group is an org-level change.
        String siteId = isBlank(req.siteId()) ? null : req.siteId().trim();
        guard.require(p, Role.ADMIN, siteId == null ? ResourceScope.org() : ResourceScope.site(siteId));
        return ResponseEntity.ok(new IdResponse(fleet.createGroup(p.tenantId(), req.name().trim(), siteId)));
    }

    @GetMapping("/api/devices")
    public ResponseEntity<?> listDevices() {
        Principal p = guard.authenticate();
        guard.require(p, Role.VIEWER, ResourceScope.org());
        return ResponseEntity.ok(fleet.listDevices(p.tenantId()));
    }

    @PostMapping("/api/devices")
    public ResponseEntity<?> registerDevice(@RequestBody RegisterDevice req) {
        Principal p = guard.authenticate();
        if (isBlank(req.serial())) {
            return badRequest("serial is required (it is the device's stable identity)");
        }
        String siteId = isBlank(req.siteId()) ? null : req.siteId().trim();
        guard.require(p, Role.ADMIN, siteId == null ? ResourceScope.org() : ResourceScope.site(siteId));
        String id = fleet.registerDevice(p.tenantId(), req.serial().trim(), req.model(), req.label(),
                req.mgmtIp(), siteId, isBlank(req.agentId()) ? null : req.agentId().trim(),
                isBlank(req.credRef()) ? null : req.credRef().trim());
        return ResponseEntity.ok(new IdResponse(id));
    }

    @PostMapping("/api/devices/{deviceId}/groups")
    public ResponseEntity<?> tagDevice(@PathVariable String deviceId, @RequestBody TagRequest req) {
        Principal p = guard.authenticate();
        if (isBlank(req.groupId())) {
            return badRequest("groupId is required");
        }
        Optional<ResourceScope> deviceScope = fleet.deviceScope(p.tenantId(), deviceId);
        if (deviceScope.isEmpty()) {
            return status404("device_not_found", deviceId);
        }
        Optional<ResourceScope> groupScope = fleet.groupScope(p.tenantId(), req.groupId().trim());
        if (groupScope.isEmpty()) {
            return status404("group_not_found", req.groupId());
        }
        // Tagging links a device into a group — it touches BOTH, so require admin on EACH. Authorizing only
        // the device would let a site-scoped admin pull a device into a group on a site they don't control.
        guard.require(p, Role.ADMIN, deviceScope.get());
        guard.require(p, Role.ADMIN, groupScope.get());
        fleet.tagDevice(p.tenantId(), deviceId, req.groupId().trim());
        return ResponseEntity.ok().build();
    }

    /** Remove a device from a group. Like tagging, it touches BOTH lineages, so require admin on EACH. */
    @DeleteMapping("/api/devices/{deviceId}/groups/{groupId}")
    public ResponseEntity<?> untagDevice(@PathVariable String deviceId, @PathVariable String groupId) {
        Principal p = guard.authenticate();
        Optional<ResourceScope> deviceScope = fleet.deviceScope(p.tenantId(), deviceId);
        if (deviceScope.isEmpty()) {
            return status404("device_not_found", deviceId);
        }
        Optional<ResourceScope> groupScope = fleet.groupScope(p.tenantId(), groupId);
        if (groupScope.isEmpty()) {
            return status404("group_not_found", groupId);
        }
        guard.require(p, Role.ADMIN, deviceScope.get());
        guard.require(p, Role.ADMIN, groupScope.get());
        fleet.untagDevice(p.tenantId(), deviceId, groupId);
        return ResponseEntity.ok().build();
    }

    /** Update a device's HiveKeeper metadata (display label and/or pinned site). Admin on the device's lineage;
     *  moving it into a site additionally requires admin on the target site (you are changing its lineage). */
    @PatchMapping("/api/devices/{deviceId}")
    public ResponseEntity<?> updateDevice(@PathVariable String deviceId, @RequestBody UpdateDevice req) {
        Principal p = guard.authenticate();
        Optional<ResourceScope> deviceScope = fleet.deviceScope(p.tenantId(), deviceId);
        if (deviceScope.isEmpty()) {
            return status404("device_not_found", deviceId);
        }
        guard.require(p, Role.ADMIN, deviceScope.get());
        String siteId = isBlank(req.siteId()) ? null : req.siteId().trim();
        if (siteId != null) {
            guard.require(p, Role.ADMIN, ResourceScope.site(siteId));
        }
        String label = isBlank(req.label()) ? null : req.label().trim();
        fleet.updateDevice(p.tenantId(), deviceId, label, siteId);
        return ResponseEntity.ok().build();
    }

    /**
     * Resolves a user's effective role on a resource (admin-only — inspecting access). Provide exactly one
     * of deviceId / groupId / siteId, or none for the organization scope.
     */
    @GetMapping("/api/access/effective")
    public ResponseEntity<?> effective(@RequestParam String userId,
                                       @RequestParam(required = false) String siteId,
                                       @RequestParam(required = false) String groupId,
                                       @RequestParam(required = false) String deviceId) {
        Principal p = guard.authenticate();
        guard.require(p, Role.ADMIN, ResourceScope.org());
        String tenant = p.tenantId();
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
