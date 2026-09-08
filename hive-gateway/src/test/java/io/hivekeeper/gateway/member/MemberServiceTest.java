package io.hivekeeper.gateway.member;

import io.hivekeeper.gateway.access.Role;
import io.hivekeeper.gateway.setup.IdpAdminClient;
import io.hivekeeper.gateway.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The membership writes in isolation (JdbcTemplate + IdP mocked): that adding a teammate creates the
 * IdP login with a TEMPORARY password and then writes the membership + a single org-scoped grant, and
 * that re-roling updates the existing grant rather than stacking a new one. Real RLS is proven in MembersIT.
 */
class MemberServiceTest {

    private static final String ISSUER = "https://issuer.test/realms/hk";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IdpAdminClient idp = mock(IdpAdminClient.class);
    private final UserService users = mock(UserService.class);
    private final MemberService members = new MemberService(jdbc, idp, users, ISSUER);

    @Test
    void addCreatesAnIdpLoginTheTeammateOwnsThenMembershipAndOrgGrant() {
        when(idp.createUser(eq("bob"), eq("b@x"), eq("pw"), eq("Bob"), eq(true)))
                .thenReturn(new IdpAdminClient.CreatedUser("idp-bob", null));
        when(users.provision(eq(ISSUER), eq("idp-bob"), eq("b@x"), eq("Bob")))
                .thenReturn(new UserService.AppUser("usr-bob", "b@x", "Bob"));

        MemberService.Added added = members.add("acme", "bob", "b@x", "pw", "Bob", Role.OPERATOR);

        assertEquals("usr-bob", added.userId());
        assertNull(added.recoveryLink());                          // Keycloak forces the change instead
        verify(idp).createUser("bob", "b@x", "pw", "Bob", true);   // the admin must not keep a usable password
        verify(jdbc).update(contains("insert into membership"), anyString(), eq("usr-bob"), eq("acme"));
        verify(jdbc).update(contains("insert into role_grant"), anyString(), anyString(), eq("acme"),
                eq("operator"));
    }

    /** The other half of the same contract: under a provider that cannot force a password change, the link the
     *  teammate needs must reach the caller instead of being dropped on the floor. */
    @Test
    void addPassesTheIdpRecoveryLinkThrough() {
        when(idp.createUser(eq("bob"), eq("b@x"), eq("pw"), eq("Bob"), eq(true)))
                .thenReturn(new IdpAdminClient.CreatedUser("idp-bob", "https://authentik/if/flow/recovery/?t=x"));
        when(users.provision(eq(ISSUER), eq("idp-bob"), eq("b@x"), eq("Bob")))
                .thenReturn(new UserService.AppUser("usr-bob", "b@x", "Bob"));

        MemberService.Added added = members.add("acme", "bob", "b@x", "pw", "Bob", Role.OPERATOR);

        assertEquals("usr-bob", added.userId());
        assertEquals("https://authentik/if/flow/recovery/?t=x", added.recoveryLink());
    }

    @Test
    void setRoleUpdatesTheExistingOrgGrantInPlace() {
        when(jdbc.queryForList(contains("select membership_id from membership"), eq(String.class), eq("usr-bob")))
                .thenReturn(List.of("mb-1"));
        when(jdbc.update(contains("update role_grant set role"), eq("admin"), eq("mb-1"))).thenReturn(1);

        assertTrue(members.setRole("acme", "usr-bob", Role.ADMIN));

        verify(jdbc).update(contains("update role_grant set role"), eq("admin"), eq("mb-1"));
        verify(jdbc, never()).update(contains("insert into role_grant"), any(), any(), any(), any());
    }

    @Test
    void setRoleReturnsFalseForSomeoneWhoIsNotAMember() {
        when(jdbc.queryForList(contains("select membership_id from membership"), eq(String.class), eq("ghost")))
                .thenReturn(List.of());
        assertFalse(members.setRole("acme", "ghost", Role.ADMIN));
        verify(jdbc, never()).update(contains("update role_grant"), any(), any());
    }
}
