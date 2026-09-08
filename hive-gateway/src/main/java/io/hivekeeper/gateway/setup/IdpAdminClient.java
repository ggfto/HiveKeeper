package io.hivekeeper.gateway.setup;

import java.util.Optional;

/**
 * Abstraction over identity provider admin operations. The gateway provisions users in the IdP (Keycloak,
 * Authentik, etc.) during first-run setup and when admins add teammates, then reads their subject claim and
 * stores it in app_user. The IdP authenticates; we authorize (org/site/group roles live in our database).
 *
 * <p>Implementations authenticate against the IdP's admin API with operator-supplied credentials and create
 * accounts. The returned subject is what appears as the {@code sub} claim in the JWTs users sign in with.
 *
 * <p>Every method here signals failure with {@link IdpAdminException} — the type is part of THIS contract, not
 * of any implementation, because the controllers catch it to map conflicts and upstream errors to status codes.
 * An implementation that threw its own type would silently downgrade a mapped 409 to an unmapped 500.
 *
 * <p>Exactly one implementation is active at a time, selected by {@code hivekeeper.idp} ({@code keycloak} by
 * default, {@code authentik} for Authentik). Both live under the {@code oidc} profile, so the property — not
 * the profile list — is what picks the provider: every other OIDC bean requires {@code oidc}, so a provider
 * expressed as a second profile would have to be activated alongside it and leave two beans for one
 * injection point.
 */
public interface IdpAdminClient {

    /**
     * Create the first-run admin: a permanent password, no pending action, able to sign in immediately.
     * Returns their subject (the {@code sub} claim of the JWTs they will sign in with).
     */
    default String createAdmin(String username, String email, String password, String displayName) {
        return createUser(username, email, password, displayName, false).subject();
    }

    /**
     * Create a user and return their subject plus, when the provider works that way, a link they must follow to
     * choose their own password.
     *
     * <p>{@code mustSetOwnPassword} is the teammate case: an admin must never be able to keep signing in as
     * someone they added. The two providers honour that differently, which is exactly why this returns a record
     * rather than a bare subject — Keycloak marks the admin's throwaway password temporary and pins an
     * {@code UPDATE_PASSWORD} action, so there is no link to hand out; Authentik has no such flag, so it
     * instead creates the account with NO usable password and issues a one-time recovery link, which the caller
     * must pass on to the new teammate.
     *
     * <p>When {@code mustSetOwnPassword} is false the password is permanent and nothing is pending — the
     * first-run admin — and {@link CreatedUser#recoveryLink()} is always null.
     */
    CreatedUser createUser(String username, String email, String password, String displayName,
                           boolean mustSetOwnPassword);

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

    /**
     * A freshly created account: its {@code subject}, and the one-time link the new teammate must follow to set
     * their own password — null when the provider forced a password change instead (Keycloak) or when no
     * password change was asked for (the first-run admin).
     */
    record CreatedUser(String subject, String recoveryLink) {}
}
