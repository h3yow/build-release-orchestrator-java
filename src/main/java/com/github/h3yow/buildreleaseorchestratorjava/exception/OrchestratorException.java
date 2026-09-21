package com.github.h3yow.buildreleaseorchestratorjava.exception;

/**
 * 编排器业务异常基类，携带可解释的原因码。
 */
public class OrchestratorException extends RuntimeException {
    private final String code;

    public OrchestratorException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
