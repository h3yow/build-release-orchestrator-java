package com.github.h3yow.buildreleaseorchestratorjava.web.dto;

/**
 * 统一错误响应：携带可解释的原因码。
 */
public class ErrorResponse {
    private final String code;
    private final String message;

    public ErrorResponse(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
}
