package io.hivekeeper.gateway.setup;

/** A first-run setup error carrying the HTTP status the controller should return (400/403/409). */
public class SetupException extends RuntimeException {
    private final int status;

    public SetupException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
