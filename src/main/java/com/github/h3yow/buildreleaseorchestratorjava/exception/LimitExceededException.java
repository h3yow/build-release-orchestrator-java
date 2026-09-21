package com.github.h3yow.buildreleaseorchestratorjava.exception;

/**
 * 超过可配置上限（运行数、制品数、缓存容量）且按策略需拒绝。
 */
public class LimitExceededException extends OrchestratorException {
    public LimitExceededException(String message) {
        super("LIMIT_EXCEEDED", message);
    }
}
