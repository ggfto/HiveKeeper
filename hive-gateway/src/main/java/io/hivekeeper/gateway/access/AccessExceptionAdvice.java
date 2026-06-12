package io.hivekeeper.gateway.access;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Renders {@link AccessException}s (401/403/400 from {@link AccessGuard}) as a JSON error body. Active in
 *  every profile, since {@link AccessGuard} is. */
@RestControllerAdvice
public class AccessExceptionAdvice {

    public record ApiError(String error, String detail) {
    }

    @ExceptionHandler(AccessException.class)
    public ResponseEntity<ApiError> onAccess(AccessException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.error(), e.detail()));
    }
}
