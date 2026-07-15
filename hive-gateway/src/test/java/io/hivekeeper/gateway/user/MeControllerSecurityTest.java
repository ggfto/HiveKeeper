package io.hivekeeper.gateway.user;

import io.hivekeeper.gateway.security.OidcSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the authentication boundary of {@code /api/me}: no token -> 401, a valid bearer JWT -> 200 with the
 * provisioned identity. Uses the spring-security-test {@code jwt()} post-processor so no IdP is needed.
 */
@WebMvcTest(MeController.class)
@Import(OidcSecurityConfig.class)
@ActiveProfiles("oidc")
@TestPropertySource(properties = {
        "hivekeeper.oidc.issuer=http://localhost/realms/hivekeeper",
        "hivekeeper.oidc.jwk-set-uri=http://localhost/realms/hivekeeper/protocol/openid-connect/certs"
})
class MeControllerSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserService users;

    @Test
    void rejectsWhenThereIsNoToken() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsANonBearerAuthorizationHeader() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Basic abc123")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTheKnownUserAndTheirOrgsWithAValidJwt() throws Exception {
        when(users.resolve(any()))
                .thenReturn(Optional.of(new UserService.AppUser("usr-owner", "owner@acme.test", "Olivia Owner")));
        when(users.memberships("usr-owner"))
                .thenReturn(List.of(new UserService.Membership("acme", "Acme Corp", "active")));

        mvc.perform(get("/api/me").with(jwt().jwt(j -> j
                        .subject("11111111-1111-1111-1111-111111111111")
                        .issuer("http://localhost/realms/hivekeeper")
                        .claim("email", "owner@acme.test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("usr-owner"))
                .andExpect(jsonPath("$.organizations[0].tenantId").value("acme"));
    }

    @Test
    void anAuthenticatedStrangerGetsTheirIdentityBackWithNoOrgsAndNoRowWritten() throws Exception {
        // The first time somebody signs in with GitHub. They are a real, authenticated person and a total
        // stranger to us: /api/me must tell them who they are and that they belong to nothing, so the console
        // can say "ask an admin for access" — and it must NOT create a row, or every GitHub account on earth
        // could grow app_user just by signing in.
        when(users.resolve(any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/me").with(jwt().jwt(j -> j
                        .subject("gh|octocat")
                        .issuer("http://localhost/realms/hivekeeper")
                        .claim("email", "octocat@github.test")
                        .claim("name", "The Octocat"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.name").value("The Octocat"))
                .andExpect(jsonPath("$.organizations").isEmpty());

        verify(users, never()).provision(any(), any(), any(), any());
    }
}
