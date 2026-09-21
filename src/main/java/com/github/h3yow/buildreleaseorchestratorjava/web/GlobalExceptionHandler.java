package com.github.h3yow.buildreleaseorchestratorjava.web;

import com.github.h3yow.buildreleaseorchestratorjava.exception.ArtifactNotFoundException;
import com.github.h3yow.buildreleaseorchestratorjava.exception.OrchestratorException;
import com.github.h3yow.buildreleaseorchestratorjava.exception.RunNotFoundException;
import com.github.h3yow.buildreleaseorchestratorjava.web.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理：不存在映射 404，冲突/拒绝映射 409，其余业务异常映射 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(OrchestratorException.class)
    public ResponseEntity<ErrorResponse> handle(OrchestratorException ex) {
        HttpStatus status;
        if (ex instanceof RunNotFoundException || ex instanceof ArtifactNotFoundException) {
            status = HttpStatus.NOT_FOUND;
        } else if ("VERSION_ALREADY_EXISTS".equals(ex.getCode())
                || "CHECKSUM_MISMATCH".equals(ex.getCode())
                || "ARTIFACT_NOT_FOUND".equals(ex.getCode())
                || "LIMIT_EXCEEDED".equals(ex.getCode())
                || "RUN_NOT_FAILED".equals(ex.getCode())
                || "INVALID_TARGET".equals(ex.getCode())) {
            status = HttpStatus.CONFLICT;
        } else {
            status = HttpStatus.BAD_REQUEST;
        }
        log.warn("[web] 请求被拒绝: code={}, message={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(status).body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }
}
