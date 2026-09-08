package io.hivekeeper.gateway.setup;

/**
 * Raised when an identity-provider Admin API call fails (auth, conflict, or transport). Deliberately owned by
 * {@link IdpAdminClient} rather than by either implementation: the controllers catch this one type and map it
 * to a status code, so swapping Keycloak for Authentik cannot silently turn a mapped 409 into an unmapped 500.
 * Implementations put the provider's name in the message, never in the type.
 */
public class IdpAdminException extends RuntimeException {
    public IdpAdminException(String message) {
        super(message);
    }
}
