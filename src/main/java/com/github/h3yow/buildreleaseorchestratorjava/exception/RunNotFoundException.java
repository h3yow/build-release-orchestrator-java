package com.github.h3yow.buildreleaseorchestratorjava.exception;

/**
 * 运行记录不存在。
 */
public class RunNotFoundException extends OrchestratorException {
    public RunNotFoundException(String message) {
        super("RUN_NOT_FOUND", message);
    }
}
