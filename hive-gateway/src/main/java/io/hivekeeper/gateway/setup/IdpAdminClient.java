package io.hivekeeper.gateway.setup;

import java.util.Optional;

/**
 * Abstraction over identity provider admin operations. The gateway provisions users in the IdP (Keycloak,
 * Authentik, etc.) during first-run setup and when admins add teammates, then reads their subject claim and
 * stores it in app_user. The IdP authenticates; we authorize (org/site/group roles live in our database).
 *
 * <p>Implementations authenticate against the IdP's admin API with operator-supplied credentials and create
 * accounts. The returned subject is what appears as the {@code sub} claim in the JWTs users sign in with.
 */
public interface IdpAdminClient {

    /**
     * Create a user with a permanent password and no pending actions, returning their subject (the {@code sub}
     * claim of the JWTs they will sign in with). Used by first-run setup for the very first admin, who must be
     * able to sign in immediately.
     */
    String createUser(String username, String email, String password, String displayName);

    /**
     * Create a user and return their subject. When {@code temporary} is true the password is marked temporary
     * and the user is forced to change it at first sign-in (admin sets a throwaway one); when false the password
     * is permanent and no action is pending (first-run admin).
     */
    String createUser(String username, String email, String password, String displayName, boolean temporary);

    /**
     * Find an existing user by exact username or e-mail. This is what makes federated login usable: someone who
     * signs in through an identity provider (GitHub, say) has no password and does not exist until their FIRST
     * sign-in creates them — so there is nothing for {@link #createUser} to create, and an admin cannot add them
     * to an organization in advance. They sign in once (and are told they belong to no organization), and the
     * admin then admits the account that by then exists. Hence: look up, do not create.
     */
    Optional<IdpUser> findUser(String usernameOrEmail);

    /** An existing IdP account. {@code subject} becomes the {@code sub} of the JWTs they sign in with. */
    record IdpUser(String subject, String email, String name) {}
}
