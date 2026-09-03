package io.hivekeeper.gateway.setup;

import io.hivekeeper.gateway.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SetupServiceTest {

    private static final String COUNT_TENANTS = "select count(*) from tenant";
    private static final String ISSUER = "http://localhost:8081/realms/hivekeeper";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IdpAdminClient idp = mock(IdpAdminClient.class);
    private final UserService users = mock(UserService.class);
    private SetupService setup;

    @BeforeEach
    void setUp() {
        setup = new SetupService(jdbc, idp, users, ISSUER);
    }

    private void uninitialized() {
        when(jdbc.queryForObject(COUNT_TENANTS, Integer.class)).thenReturn(0);
    }

    private int statusOf(Runnable r) {
        try {
            r.run();
            return 200;
        } catch (SetupException e) {
            return e.status();
        }
    }

    @Test
    void isInitializedReflectsTheTenantCount() {
        when(jdbc.queryForObject(COUNT_TENANTS, Integer.class)).thenReturn(0, 2);
        assertFalse(setup.isInitialized());
        assertTrue(setup.isInitialized());
    }

    @Test
    void setupCreatesTheIdpAdminTheOrgAndAnOwnerGrant() {
        uninitialized();
        when(idp.createUser("admin", "a@x", "pw", "admin")).thenReturn("idp-123");
        when(users.provision(eq(ISSUER), eq("idp-123"), any(), eq("admin")))
                .thenReturn(new UserService.AppUser("usr-1", "a@x", "admin"));

        // no display name -> defaults to the username
        SetupService.SetupResult result = setup.setup(setup.setupToken(), "Acme Corp", "admin", "pw", "a@x", null);

        assertEquals("acme-corp", result.tenantId());                 // org name -> url-safe tenant id
        verify(idp).createUser("admin", "a@x", "pw", "admin");        // admin created in IdP first
        verify(jdbc).update(contains("insert into tenant"), eq("acme-corp"), eq("Acme Corp"), anyString());
        verify(jdbc).update(contains("insert into membership"), anyString(), eq("usr-1"), eq("acme-corp"));
        verify(jdbc).update(contains("insert into role_grant"), anyString(), anyString(), eq("acme-corp"));
    }

    @Test
    void rejectsAnInvalidSetupTokenBeforeTouchingIdp() {
        assertEquals(403, statusOf(() -> setup.setup("wrong-token", "Acme", "admin", "pw", "a@x", null)));
        verifyNoInteractions(idp);
    }

    @Test
    void refusesOnceAlreadyInitialized() {
        when(jdbc.queryForObject(COUNT_TENANTS, Integer.class)).thenReturn(1);
        assertEquals(409, statusOf(() -> setup.setup(setup.setupToken(), "Acme", "admin", "pw", "a@x", null)));
        verifyNoInteractions(idp);   // locked: the first org already exists
    }

    @Test
    void requiresAnOrgNameAndAdminCredentials() {
        uninitialized();
        assertEquals(400, statusOf(() -> setup.setup(setup.setupToken(), "  ", "admin", "pw", null, null)));
        assertEquals(400, statusOf(() -> setup.setup(setup.setupToken(), "Acme", "admin", "  ", null, null)));
        verifyNoInteractions(idp);
    }
}
