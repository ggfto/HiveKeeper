package io.hivekeeper.gateway.fleet;

import io.hivekeeper.gateway.fleet.FleetController.ApiError;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns persistence constraint violations from the fleet endpoints into clean 4xx responses instead of a bare
 * 500. A duplicate site name or device serial is a 409 conflict; a reference to a site/group/device that
 * does not exist in this organization (including a blocked cross-tenant reference, rejected by the composite
 * foreign keys under Postgres) is a 400. Scoped to {@link FleetController} so it does not change error handling
 * elsewhere. Always present: the in-memory store raises the same {@link DuplicateKeyException} for a duplicate
 * name/serial, so the demo stack reports conflicts identically.
 */
@RestControllerAdvice(assignableTypes = FleetController.class)
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
