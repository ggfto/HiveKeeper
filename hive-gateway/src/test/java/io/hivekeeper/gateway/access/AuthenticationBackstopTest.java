package io.hivekeeper.gateway.access;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the fail-closed guarantee: a handler that forgets {@code guard.authenticate()} is still not reachable
 * without credentials.
 *
 * <p>{@link ForgetfulController} is the whole point of this test — it is exactly the mistake the codebase used to
 * be one careless commit away from: an {@code /api/**} endpoint with no authentication call at all. Before the
 * backstop it answered 200 to anyone.
 */
@WebMvcTest(AuthenticationBackstopTest.ForgetfulController.class)
@Import({AccessExceptionAdvice.class, AuthenticationBackstopTest.ForgetfulController.class})
@AutoConfigureMockMvc(addFilters = false)
class AuthenticationBackstopTest {

    /** A controller written the wrong way: no {@code guard.authenticate()}, no {@code guard.require(...)}. */
    @RestController
    static class ForgetfulController {

        @GetMapping("/api/forgot-to-authenticate")
        String secrets() {
            return "the crown jewels";
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AccessGuard guard;

    @Test
    void anUnauthenticatedCallerCannotReachAHandlerThatForgotToAuthenticate() throws Exception {
        doThrow(new AccessException(401, "unauthorized", "provide an X-Tenant-Key or a bearer token + X-Org"))
                .when(guard).authenticate();

        mvc.perform(get("/api/forgot-to-authenticate")).andExpect(status().isUnauthorized());
    }

    @Test
    void anAuthenticatedCallerStillReachesIt() throws Exception {
        // The backstop authenticates; it does not authorize. A handler that forgets require() is still reachable
        // by any member of the org — which is why this returns 200 and not 403.
        when(guard.authenticate()).thenReturn(Principal.owner("acme"));

        mvc.perform(get("/api/forgot-to-authenticate")).andExpect(status().isOk());
    }

    @Test
    void aPublicPathIsNotAuthenticated() throws Exception {
        // /api/mode is on the public list, so the backstop must not even ask the guard.
        mvc.perform(get("/api/mode"));

        verify(guard, never()).authenticate();
    }

    /**
     * The public list is the gateway's entire unauthenticated attack surface. Pinning it makes growing that
     * surface a deliberate act that shows up in a diff, rather than a line nobody notices.
     */
    @Test
    void thePublicSurfaceIsExactlyTheseFivePathsPlusEnrollmentBootstrap() {
        assertEquals(
                Set.of("/api/mode", "/api/setup", "/api/setup/status", "/api/me",
                        "/api/enrollments/certificate/renew"),
                AuthenticationBackstop.PUBLIC_PATHS);

        // The bootstrap endpoint carries its one-time token in the path.
        assertTrue(AuthenticationBackstop.isPublic("/api/enrollments/abc123/certificate"));
    }

    @Test
    void theEnrollmentPatternDoesNotOpenNeighbouringPaths() {
        assertFalse(AuthenticationBackstop.isPublic("/api/enrollments"));
        assertFalse(AuthenticationBackstop.isPublic("/api/enrollments/abc123"));
        assertFalse(AuthenticationBackstop.isPublic("/api/enrollments/abc123/certificate/extra"));
        // A path traversal must not smuggle a guarded endpoint past the pattern.
        assertFalse(AuthenticationBackstop.isPublic("/api/enrollments/a/b/certificate"));
        assertFalse(AuthenticationBackstop.isPublic("/api/devices"));
        assertFalse(AuthenticationBackstop.isPublic("/api/agents/lab-agent/set-credential"));
    }
}
