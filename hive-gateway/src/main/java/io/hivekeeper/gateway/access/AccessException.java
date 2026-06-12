package io.hivekeeper.gateway.access;

/** An authentication/authorization failure carrying the HTTP status to return. Mapped to a JSON body by
 *  {@link AccessExceptionAdvice}. */
public class AccessException extends RuntimeException {

    private final int status;
    private final String error;

    public AccessException(int status, String error, String detail) {
        super(detail);
        this.status = status;
        this.error = error;
    }

    public int status() {
        return status;
    }

    public String error() {
        return error;
    }

    public String detail() {
        return getMessage();
    }
}
