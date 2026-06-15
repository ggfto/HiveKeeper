package io.hivekeeper.gateway.setup;

/** Raised when a Keycloak Admin API call during first-run setup fails (auth, conflict, or transport). */
public class KeycloakAdminException extends RuntimeException {
    public KeycloakAdminException(String message) {
        super(message);
    }
}
