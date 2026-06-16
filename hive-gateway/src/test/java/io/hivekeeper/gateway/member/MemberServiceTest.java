package io.hivekeeper.gateway.member;

import io.hivekeeper.gateway.access.Role;
import io.hivekeeper.gateway.setup.KeycloakAdminClient;
import io.hivekeeper.gateway.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The membership writes in isolation (JdbcTemplate + Keycloak mocked): that adding a teammate creates the
 * Keycloak login with a TEMPORARY password and then writes the membership + a single org-scoped grant, and
 * that re-roling updates the existing grant rather than stacking a new one. Real RLS is proven in MembersIT.
 */
class MemberServiceTest {

    private static final String ISSUER = "https://issuer.test/realms/hk";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
    private final UserService users = mock(UserService.class);
    private final MemberService members = new MemberService(jdbc, keycloak, users, ISSUER);

    @Test
    void addCreatesAKeycloakLoginWithATempPasswordThenMembershipAndOrgGrant() {
        when(keycloak.createUser(eq("bob"), eq("b@x"), eq("pw"), eq("Bob"), eq(true))).thenReturn("kc-bob");
        when(users.provision(eq(ISSUER), eq("kc-bob"), eq("b@x"), eq("Bob")))
                .thenReturn(new UserService.AppUser("usr-bob", "b@x", "Bob"));

        String userId = members.add("acme", "bob", "b@x", "pw", "Bob", Role.OPERATOR);

        assertEquals("usr-bob", userId);
        verify(keycloak).createUser("bob", "b@x", "pw", "Bob", true);   // temporary password => forced reset
        verify(jdbc).update(contains("insert into membership"), anyString(), eq("usr-bob"), eq("acme"));
        verify(jdbc).update(contains("insert into role_grant"), anyString(), anyString(), eq("acme"),
                eq("operator"));
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
