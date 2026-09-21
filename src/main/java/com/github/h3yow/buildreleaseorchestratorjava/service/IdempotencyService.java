package com.github.h3yow.buildreleaseorchestratorjava.service;

import java.util.function.Supplier;

import org.springframework.stereotype.Service;

/**
 * 幂等键登记：同一幂等键只产生一次真实执行，重复提交返回首次结果并标记为重复。
 */
@Service
public class IdempotencyService {

    public static class IdempotentResult<T> {
        private final T value;
        private final boolean duplicated;

        public IdempotentResult(T value, boolean duplicated) {
            this.value = value;
            this.duplicated = duplicated;
        }

        public T getValue() { return value; }
        public boolean isDuplicated() { return duplicated; }
    }

    /**
     * 按幂等键执行：首次执行 supplier 并登记，重复提交直接返回首次结果。
     */
    public <T> IdempotentResult<T> execute(String scope, String idempotencyKey, Supplier<T> supplier) {
        // TODO: 实现
        return null;
    }
}
