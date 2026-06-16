package io.hivekeeper.gateway.member;

import io.hivekeeper.gateway.access.AccessGuard;
import io.hivekeeper.gateway.access.Principal;
import io.hivekeeper.gateway.access.ResourceScope;
import io.hivekeeper.gateway.access.Role;
import io.hivekeeper.gateway.setup.KeycloakAdminException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.Optional;

/**
 * Organization member management — list the people in the active org, add a teammate, change their role, or
 * remove them. Managing members needs {@code admin}; anything that touches an OWNER (creating one, promoting
 * to or demoting from owner, or removing one) needs {@code owner} — an admin can never escalate past their own
 * authority. The last remaining owner can be neither demoted nor removed, so an org can never be orphaned.
 * Only present under the {@code oidc} profile.
 */
@RestController
@Profile("oidc")
public class MemberController {

    public record AddMember(String username, String email, String password, String name, String role) {
    }

    public record UpdateMember(String role) {
    }

    public record MemberResponse(String userId) {
    }

    public record ApiError(String error, String detail) {
    }

    private final AccessGuard guard;
    private final MemberService members;

    public MemberController(AccessGuard guard, MemberService members) {
        this.guard = guard;
        this.members = members;
    }

    @GetMapping("/api/members")
    public ResponseEntity<?> list() {
        Principal p = guard.authenticate();
        guard.require(p, Role.ADMIN, ResourceScope.org());
        return ResponseEntity.ok(members.list(p.tenantId()));
    }

    @PostMapping("/api/members")
    public ResponseEntity<?> add(@RequestBody AddMember req) {
        Principal p = guard.authenticate();
        if (isBlank(req.username()) || isBlank(req.password())) {
            return badRequest("a username and password are required");
        }
        Role role = parseRole(req.role());
        if (role == null) {
            return badRequest("role must be one of viewer, operator, admin, owner");
        }
        // Managing members is an admin action; minting an OWNER is reserved to owners (no privilege escalation).
        guard.require(p, role == Role.OWNER ? Role.OWNER : Role.ADMIN, ResourceScope.org());
        try {
            String userId = members.add(p.tenantId(), req.username().trim(), req.email(), req.password(),
                    req.name(), role);
            return ResponseEntity.ok(new MemberResponse(userId));
        } catch (KeycloakAdminException e) {
            return ResponseEntity.status(409).body(new ApiError("user_exists", e.getMessage()));
        }
    }

    @PatchMapping("/api/members/{userId}")
    public ResponseEntity<?> update(@PathVariable String userId, @RequestBody UpdateMember req) {
        Principal p = guard.authenticate();
        Role role = parseRole(req.role());
        if (role == null) {
            return badRequest("role must be one of viewer, operator, admin, owner");
        }
        Optional<Role> current = members.orgRole(p.tenantId(), userId);
        if (current.isEmpty()) {
            return notFound(userId);
        }
        // Promoting to OR demoting from owner is owner-only; any other change is an admin action.
        boolean touchesOwner = role == Role.OWNER || current.get() == Role.OWNER;
        guard.require(p, touchesOwner ? Role.OWNER : Role.ADMIN, ResourceScope.org());
        if (current.get() == Role.OWNER && role != Role.OWNER && members.ownerCount(p.tenantId()) <= 1) {
            return conflict("cannot demote the last owner of the organization");
        }
        members.setRole(p.tenantId(), userId, role);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/api/members/{userId}")
    public ResponseEntity<?> remove(@PathVariable String userId) {
        Principal p = guard.authenticate();
        Optional<Role> current = members.orgRole(p.tenantId(), userId);
        if (current.isEmpty()) {
            return notFound(userId);
        }
        guard.require(p, current.get() == Role.OWNER ? Role.OWNER : Role.ADMIN, ResourceScope.org());
        if (current.get() == Role.OWNER && members.ownerCount(p.tenantId()) <= 1) {
            return conflict("cannot remove the last owner of the organization");
        }
        members.remove(p.tenantId(), userId);
        return ResponseEntity.ok().build();
    }

    /** Parse a role string; blank defaults to viewer (least privilege), an unknown value returns null (400). */
    private static Role parseRole(String value) {
        if (value == null || value.isBlank()) {
            return Role.VIEWER;
        }
        try {
            return Role.of(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseEntity<?> badRequest(String detail) {
        return ResponseEntity.badRequest().body(new ApiError("bad_request", detail));
    }

    private static ResponseEntity<?> notFound(String userId) {
        return ResponseEntity.status(404).body(new ApiError("member_not_found", userId));
    }

    private static ResponseEntity<?> conflict(String detail) {
        return ResponseEntity.status(409).body(new ApiError("last_owner", detail));
    }
}
