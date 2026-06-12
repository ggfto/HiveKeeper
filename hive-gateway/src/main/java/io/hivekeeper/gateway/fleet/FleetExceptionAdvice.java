package io.hivekeeper.gateway.fleet;

import io.hivekeeper.gateway.fleet.FleetController.ApiError;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns database constraint violations from the fleet endpoints into clean 4xx responses instead of a bare
 * 500. A duplicate site name or device serial is a 409 conflict; a reference to a site/group/device that
 * does not exist in this organization (including a blocked cross-tenant reference, now rejected by the
 * composite foreign keys) is a 400. Scoped to {@link FleetController} so it does not change error handling
 * elsewhere.
 */
@RestControllerAdvice(assignableTypes = FleetController.class)
@Profile("postgres")
public class FleetExceptionAdvice {

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> onDuplicate(DuplicateKeyException e) {
        return ResponseEntity.status(409).body(new ApiError("conflict",
                "a resource with that name or serial already exists in this organization"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> onIntegrity(DataIntegrityViolationException e) {
        return ResponseEntity.badRequest().body(new ApiError("bad_request",
                "referenced site, group, or device does not exist in this organization"));
    }
}
