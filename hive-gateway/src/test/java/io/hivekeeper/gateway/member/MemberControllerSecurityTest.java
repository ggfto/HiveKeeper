package io.hivekeeper.gateway.member;

import io.hivekeeper.gateway.access.AccessExceptionAdvice;
import io.hivekeeper.gateway.access.AccessGuard;
import io.hivekeeper.gateway.access.Principal;
import io.hivekeeper.gateway.access.ResourceScope;
import io.hivekeeper.gateway.access.Role;
import io.hivekeeper.gateway.setup.KeycloakAdminException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP enforcement contract of the member endpoints: managing members needs admin, anything touching an
 * owner needs owner, the last owner is protected, and a duplicate Keycloak login renders 409. Guard + service
 * are mocked so this is a fast, DB-free slice test (the real RLS writes are proven in MembersIT).
 */
@WebMvcTest(MemberController.class)
@Import(AccessExceptionAdvice.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("oidc")
class MemberControllerSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AccessGuard guard;
    @MockitoBean
    private MemberService members;

    private final Principal principal = Principal.user("acme", "usr-1");

    @BeforeEach
    void authed() {
        when(guard.authenticate()).thenReturn(principal);
    }

    @Test
    void listRequiresAdminOnTheOrg() throws Exception {
        when(members.list("acme")).thenReturn(List.of());
        mvc.perform(get("/api/members")).andExpect(status().isOk());
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.org()));
    }

    @Test
    void addingAViewerRequiresAdminOnTheOrg() throws Exception {
        when(members.add(eq("acme"), eq("bob"), any(), eq("pw"), any(), eq(Role.VIEWER))).thenReturn("usr-bob");
        mvc.perform(post("/api/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"pw\",\"role\":\"viewer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("usr-bob"));
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.org()));
    }

    @Test
    void addingAnOwnerRequiresOwnerOnTheOrg() throws Exception {
        when(members.add(eq("acme"), eq("ann"), any(), eq("pw"), any(), eq(Role.OWNER))).thenReturn("usr-ann");
        mvc.perform(post("/api/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ann\",\"password\":\"pw\",\"role\":\"owner\"}"))
                .andExpect(status().isOk());
        verify(guard).require(eq(principal), eq(Role.OWNER), eq(ResourceScope.org()));
    }

    @Test
    void addingRejectsAMissingUsername() throws Exception {
        mvc.perform(post("/api/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"viewer\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(members);
    }

    @Test
    void noPasswordAdmitsAnAccountThatAlreadyExists() throws Exception {
        // The federated-login path. Someone who signs in with GitHub has no password and no Keycloak account
        // until their first sign-in creates one, so an admin cannot CREATE them — only admit them afterwards.
        when(members.invite("acme", "octocat", Role.VIEWER)).thenReturn(Optional.of("usr-9"));

        mvc.perform(post("/api/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"octocat\",\"role\":\"viewer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("usr-9"));

        verify(guard).require(principal, Role.ADMIN, ResourceScope.org());
        // No new login is minted — that is the whole distinction between this and the password path.
        verify(members, never()).add(any(), any(), any(), any(), any(), any());
    }

    @Test
    void invitingSomeoneWhoHasNeverSignedInIs404() throws Exception {
        when(members.invite("acme", "ghost", Role.VIEWER)).thenReturn(Optional.empty());

        mvc.perform(post("/api/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\",\"role\":\"viewer\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("user_not_found"));
    }

    @Test
    void invitingSomeoneAlreadyInTheOrgIs409() throws Exception {
        when(members.invite("acme", "octocat", Role.VIEWER))
                .thenThrow(new MemberService.AlreadyAMemberException("octocat"));

        mvc.perform(post("/api/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"octocat\",\"role\":\"viewer\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("already_a_member"));
    }

    @Test
    void invitingAsOwnerStillRequiresOwner() throws Exception {
        // The invite path must not become a way around the no-privilege-escalation rule.
        when(members.invite("acme", "octocat", Role.OWNER)).thenReturn(Optional.of("usr-9"));

        mvc.perform(post("/api/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"octocat\",\"role\":\"owner\"}"))
                .andExpect(status().isOk());

        verify(guard).require(principal, Role.OWNER, ResourceScope.org());
    }

    @Test
    void aDuplicateKeycloakLoginRenders409() throws Exception {
        when(members.add(any(), any(), any(), any(), any(), any()))
                .thenThrow(new KeycloakAdminException("a user 'bob' already exists in realm hivekeeper"));
        mvc.perform(post("/api/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"pw\",\"role\":\"viewer\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("user_exists"));
    }

    @Test
    void changingAMemberToAdminRequiresAdmin() throws Exception {
        when(members.orgRole("acme", "usr-2")).thenReturn(Optional.of(Role.VIEWER));
        mvc.perform(patch("/api/members/usr-2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"admin\"}"))
                .andExpect(status().isOk());
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.org()));
        verify(members).setRole("acme", "usr-2", Role.ADMIN);
    }

    @Test
    void changingAnOwnerRequiresOwner() throws Exception {
        when(members.orgRole("acme", "usr-2")).thenReturn(Optional.of(Role.OWNER));
        when(members.ownerCount("acme")).thenReturn(2);
        mvc.perform(patch("/api/members/usr-2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"admin\"}"))
                .andExpect(status().isOk());
        verify(guard).require(eq(principal), eq(Role.OWNER), eq(ResourceScope.org()));
    }

    @Test
    void demotingTheLastOwnerIsRefused() throws Exception {
        when(members.orgRole("acme", "usr-2")).thenReturn(Optional.of(Role.OWNER));
        when(members.ownerCount("acme")).thenReturn(1);
        mvc.perform(patch("/api/members/usr-2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"admin\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("last_owner"));
        verify(members, never()).setRole(any(), any(), any());
    }

    @Test
    void updatingAnUnknownMemberIs404() throws Exception {
        when(members.orgRole("acme", "ghost")).thenReturn(Optional.empty());
        mvc.perform(patch("/api/members/ghost").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"admin\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void removingAMemberRequiresAdmin() throws Exception {
        when(members.orgRole("acme", "usr-2")).thenReturn(Optional.of(Role.OPERATOR));
        mvc.perform(delete("/api/members/usr-2")).andExpect(status().isOk());
        verify(guard).require(eq(principal), eq(Role.ADMIN), eq(ResourceScope.org()));
        verify(members).remove("acme", "usr-2");
    }

    @Test
    void removingTheLastOwnerIsRefused() throws Exception {
        when(members.orgRole("acme", "usr-2")).thenReturn(Optional.of(Role.OWNER));
        when(members.ownerCount("acme")).thenReturn(1);
        mvc.perform(delete("/api/members/usr-2")).andExpect(status().isConflict());
        verify(members, never()).remove(any(), any());
    }
}
