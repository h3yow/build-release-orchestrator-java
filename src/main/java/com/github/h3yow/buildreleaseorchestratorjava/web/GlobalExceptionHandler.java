package com.github.h3yow.buildreleaseorchestratorjava.web;

import com.github.h3yow.buildreleaseorchestratorjava.error.OrchestratorException;
import com.github.h3yow.buildreleaseorchestratorjava.model.ErrorView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrchestratorException.class)
    public ResponseEntity<ErrorView> handle(OrchestratorException ex) {
        HttpStatus status = switch (ex.getErrorCode()) {
            case PIPELINE_NOT_FOUND, RUN_NOT_FOUND, ARTIFACT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENT_KEY_CONFLICT, VERSION_ALREADY_EXISTS, ARTIFACT_CHECKSUM_MISMATCH,
                 VERSION_CONFLICT, RUN_NOT_RETRYABLE, RUN_LIMIT_EXCEEDED,
                 ARTIFACT_LIMIT_EXCEEDED, CACHE_LIMIT_EXCEEDED, ROLLBACK_TARGET_UNAVAILABLE ->
                    HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ErrorView(ex.getErrorCode(), ex.getMessage()));
    }
}
