package com.github.h3yow.buildreleaseorchestratorjava.error;

public class OrchestratorException extends RuntimeException {

    private final ErrorCode errorCode;

    public OrchestratorException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
