package io.hivekeeper.gateway.fleet;

import io.hivekeeper.gateway.access.AccessGuard;
import io.hivekeeper.gateway.access.AccessService;
import io.hivekeeper.gateway.access.Principal;
import io.hivekeeper.gateway.access.ResourceScope;
import io.hivekeeper.gateway.access.Role;
import io.hivekeeper.gateway.enroll.CertificateAuthority;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Optional;

/**
 * The organization's fleet + access API. Every endpoint resolves the caller via {@link AccessGuard} (a
 * user's bearer JWT + active org, or the {@code X-Tenant-Key} service principal) and requires a scoped role:
 * reads need {@code viewer}, structural changes need {@code admin}. Cross-tenant writes are additionally
 * impossible at the database (composite FKs). Always present: it is backed by {@link PostgresFleetService}
 * under the {@code postgres} profile and by the in-memory store otherwise, so the fleet UI works in every
 * deployment (the dev/demo stack included).
 */
@RestController
public class FleetController {

    public record CreateSite(String name) {
    }

    public record UpdateSite(String name) {
    }

    public record CreateGroup(String name, String siteId) {
    }

    public record UpdateGroup(String name, String siteId) {
    }

    public record RegisterDevice(String serial, String model, String label, String mgmtIp,
                                 String siteId, String agentId, String credRef) {
    }

    public record TagRequest(String groupId) {
    }

    public record UpdateDevice(String label, String siteId) {
    }

    public record EnrollAgent(String agentId, String siteId) {
    }

    public record AgentRef(String agentId) {
    }

    /** {@code caPem} is the CA certificate the agent must trust (its ca.pem) — public, not a secret. Null when
     *  the gateway has no CA configured (no mTLS enrollment). Returned so the operator gets it here instead of
     *  reading it out of a container log. */
    public record EnrollmentResponse(String agentId, String token, String caPem) {
    }

    public record IdResponse(String id) {
    }

    public record EffectiveResponse(String userId, String scopeKind, String scopeId, String role) {
    }

    public record ApiError(String error, String detail) {
    }

    private final AccessGuard guard;
    private final FleetService fleet;
    // Optional: the per-user grant resolver only exists under postgres; the in-memory stack authorizes via the
    // X-Tenant-Key service principal (owner), so the access-inspection endpoint simply degrades when it is absent.
    private final ObjectProvider<AccessService> access;
    // Optional: the CA only exists under the mtls profile. When absent, enrollment still mints a token but the
    // response carries no ca.pem (a token-only/dev deployment).
    private final ObjectProvider<CertificateAuthority> certificateAuthority;

    public FleetController(AccessGuard guard, FleetService fleet, ObjectProvider<AccessService> access,
                           ObjectProvider<CertificateAuthority> certificateAuthority) {
        this.guard = guard;
        this.fleet = fleet;
        this.access = access;
        this.certificateAuthority = certificateAuthority;
    }

    /** The CA the agent must trust, as PEM — public, not a secret. Null when no CA is configured. */
    private String caPem() {
        CertificateAuthority ca = certificateAuthority.getIfAvailable();
        return ca == null ? null : ca.caPem();
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

    /** Rename a site. Org-admin (matching site creation); a duplicate name is a 409. */
    @PatchMapping("/api/sites/{siteId}")
    public ResponseEntity<?> updateSite(@PathVariable String siteId, @RequestBody UpdateSite req) {
        Principal p = guard.authenticate();
        guard.require(p, Role.ADMIN, ResourceScope.org());
        if (isBlank(req.name())) {
            return badRequest("name is required");
        }
        if (!fleet.siteExists(p.tenantId(), siteId)) {
            return status404("site_not_found", siteId);
        }
        try {
            fleet.renameSite(p.tenantId(), siteId, req.name().trim());
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(409)
                    .body(new ApiError("site_exists", "a site named '" + req.name().trim() + "' already exists"));
        }
        return ResponseEntity.ok().build();
    }

    /** Delete a site. Org-admin, and only when empty — the site FKs have no cascade by design, so its devices
     *  and groups must be moved or removed first (we report how many are blocking rather than fail on the FK). */
    @DeleteMapping("/api/sites/{siteId}")
    public ResponseEntity<?> deleteSite(@PathVariable String siteId) {
        Principal p = guard.authenticate();
        guard.require(p, Role.ADMIN, ResourceScope.org());
        if (!fleet.siteExists(p.tenantId(), siteId)) {
            return status404("site_not_found", siteId);
        }
        int dependents = fleet.siteDependents(p.tenantId(), siteId);
        if (dependents > 0) {
            return ResponseEntity.status(409).body(new ApiError("site_not_empty",
                    "move or remove this site's " + dependents + " device(s)/group(s) first"));
        }
        fleet.deleteSite(p.tenantId(), siteId);
        return ResponseEntity.ok().build();
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

    /**
     * Rename and/or move a group. The body carries the group's full desired state — its name and its pinned
     * site ({@code siteId} null/blank = a cross-site tag). Re-pinning changes the group's lineage, so this
     * requires admin on the group AS IT IS NOW (its current site, or the org for a cross-site tag) AND on where
     * it is GOING (the target site, or the org), mirroring how moving a device into a site is authorized.
     */
    @PatchMapping("/api/groups/{groupId}")
    public ResponseEntity<?> updateGroup(@PathVariable String groupId, @RequestBody UpdateGroup req) {
        Principal p = guard.authenticate();
        if (isBlank(req.name())) {
            return badRequest("name is required");
        }
        Optional<ResourceScope> scope = fleet.groupScope(p.tenantId(), groupId);
        if (scope.isEmpty()) {
            return status404("group_not_found", groupId);
        }
        guard.require(p, Role.ADMIN, scope.get());                          // control the group as it is now
        String siteId = isBlank(req.siteId()) ? null : req.siteId().trim();
        guard.require(p, Role.ADMIN, siteId == null ? ResourceScope.org() : ResourceScope.site(siteId)); // and the target
        fleet.updateGroup(p.tenantId(), groupId, req.name().trim(), siteId);
        return ResponseEntity.ok().build();
    }

    /** Delete a group (its device tags cascade away; the devices remain). Admin on the group's lineage. */
    @DeleteMapping("/api/groups/{groupId}")
    public ResponseEntity<?> deleteGroup(@PathVariable String groupId) {
        Principal p = guard.authenticate();
        Optional<ResourceScope> scope = fleet.groupScope(p.tenantId(), groupId);
        if (scope.isEmpty()) {
            return status404("group_not_found", groupId);
        }
        guard.require(p, Role.ADMIN, scope.get());
        fleet.deleteGroup(p.tenantId(), groupId);
        return ResponseEntity.ok().build();
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

    /** Every agent enrolled in the org, by durable identity — connected or not (distinct from {@code
     *  GET /api/agents}, which is only the currently-connected set). Any member with org viewer may read it. */
    @GetMapping("/api/agents/all")
    public ResponseEntity<?> listAgents() {
        Principal p = guard.authenticate();
        guard.require(p, Role.VIEWER, ResourceScope.org());
        return ResponseEntity.ok(fleet.listAgents(p.tenantId()));
    }

    /** The agents that can reach a device (its reachability set). Viewer on the device's lineage. */
    @GetMapping("/api/devices/{deviceId}/agents")
    public ResponseEntity<?> listDeviceAgents(@PathVariable String deviceId) {
        Principal p = guard.authenticate();
        Optional<ResourceScope> deviceScope = fleet.deviceScope(p.tenantId(), deviceId);
        if (deviceScope.isEmpty()) {
            return status404("device_not_found", deviceId);
        }
        guard.require(p, Role.VIEWER, deviceScope.get());
        return ResponseEntity.ok(fleet.reachableAgents(p.tenantId(), deviceId));
    }

    /** Add an agent to a device's reachability set — declaring that this agent can also drive the AP. Like
     *  tagging, it links a device to another resource, so require admin on BOTH the device's lineage AND the
     *  agent's site (a site-scoped admin cannot pull in an agent on a site they do not control). */
    @PostMapping("/api/devices/{deviceId}/agents")
    public ResponseEntity<?> addDeviceAgent(@PathVariable String deviceId, @RequestBody AgentRef req) {
        Principal p = guard.authenticate();
        if (isBlank(req.agentId())) {
            return badRequest("agentId is required");
        }
        Optional<ResourceScope> deviceScope = fleet.deviceScope(p.tenantId(), deviceId);
        if (deviceScope.isEmpty()) {
            return status404("device_not_found", deviceId);
        }
        Optional<FleetService.AgentSummary> agent = agent(p.tenantId(), req.agentId().trim());
        if (agent.isEmpty()) {
            return status404("agent_not_found", req.agentId());
        }
        guard.require(p, Role.ADMIN, deviceScope.get());
        guard.require(p, Role.ADMIN, agentScope(agent.get()));
        fleet.addDeviceAgent(p.tenantId(), deviceId, req.agentId().trim());
        return ResponseEntity.ok().build();
    }

    /** Remove an agent from a device's reachability set. Like adding, it touches BOTH lineages — admin on each. */
    @DeleteMapping("/api/devices/{deviceId}/agents/{agentId}")
    public ResponseEntity<?> removeDeviceAgent(@PathVariable String deviceId, @PathVariable String agentId) {
        Principal p = guard.authenticate();
        Optional<ResourceScope> deviceScope = fleet.deviceScope(p.tenantId(), deviceId);
        if (deviceScope.isEmpty()) {
            return status404("device_not_found", deviceId);
        }
        Optional<FleetService.AgentSummary> agent = agent(p.tenantId(), agentId);
        if (agent.isEmpty()) {
            return status404("agent_not_found", agentId);
        }
        guard.require(p, Role.ADMIN, deviceScope.get());
        guard.require(p, Role.ADMIN, agentScope(agent.get()));
        fleet.removeDeviceAgent(p.tenantId(), deviceId, agentId);
        return ResponseEntity.ok().build();
    }

    private Optional<FleetService.AgentSummary> agent(String tenantId, String agentId) {
        return fleet.listAgents(tenantId).stream().filter(a -> a.agentId().equals(agentId)).findFirst();
    }

    private static ResourceScope agentScope(FleetService.AgentSummary agent) {
        return agent.siteId() == null ? ResourceScope.org() : ResourceScope.site(agent.siteId());
    }

    /**
     * Register a new agent and return its one-time enrollment token (the operator configures the on-prem agent
     * with it). Admin on the org, or on the site when the agent is pinned to one. The token is returned ONCE;
     * the agent appears in the connected-agents list only after it dials in with it.
     */
    @PostMapping("/api/enrollments")
    public ResponseEntity<?> enrollAgent(@RequestBody EnrollAgent req) {
        Principal p = guard.authenticate();
        if (isBlank(req.agentId())) {
            return badRequest("agentId is required");
        }
        String siteId = isBlank(req.siteId()) ? null : req.siteId().trim();
        guard.require(p, Role.ADMIN, siteId == null ? ResourceScope.org() : ResourceScope.site(siteId));
        try {
            String token = fleet.createEnrollment(p.tenantId(), req.agentId().trim(), siteId);
            return ResponseEntity.ok(new EnrollmentResponse(req.agentId().trim(), token, caPem()));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.status(409)
                    .body(new ApiError("agent_exists", "an agent '" + req.agentId().trim() + "' is already enrolled"));
        } catch (UnsupportedOperationException e) {
            // the in-memory dev/demo stack cannot mint enrollments (they live in the agent-handshake auth store)
            return ResponseEntity.status(501).body(new ApiError("not_supported", e.getMessage()));
        }
    }

    /**
     * The CA certificate (ca.pem) an agent must trust to reach the gateway — public, not a secret. Lets the
     * console re-offer it for download without minting a new enrollment, e.g. to fix a lost/garbled ca.pem on an
     * already-enrolled agent. 404 when the gateway has no CA (a token-only/dev deployment). Any authenticated
     * member may read it.
     */
    @GetMapping(value = "/api/enrollments/ca", produces = "application/x-pem-file")
    public ResponseEntity<String> enrollmentCa() {
        guard.authenticate();
        String pem = caPem();
        if (pem == null) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"no_ca\",\"detail\":\"this gateway has no CA (no mTLS enrollment)\"}");
        }
        return ResponseEntity.ok(pem);
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
        AccessService resolver = access.getIfAvailable();
        if (resolver == null) {
            return ResponseEntity.status(501).body(new ApiError("not_supported",
                    "effective-role inspection requires the 'postgres' profile"));
        }
        String role = resolver.effectiveRole(tenant, userId, scope).map(Role::name).orElse(null);
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
