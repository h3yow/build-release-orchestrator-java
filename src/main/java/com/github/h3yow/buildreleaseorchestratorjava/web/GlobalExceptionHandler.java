package com.github.h3yow.buildreleaseorchestratorjava.web;

import com.github.h3yow.buildreleaseorchestratorjava.exception.OrchestratorException;
import com.github.h3yow.buildreleaseorchestratorjava.web.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理：业务异常映射为 409/404 并携带原因码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(OrchestratorException.class)
    public ResponseEntity<ErrorResponse> handle(OrchestratorException ex) {
        // TODO: 实现
        return null;
    }
}
