package com.github.h3yow.buildreleaseorchestratorjava.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 幂等键登记：同一幂等键只产生一次真实执行，重复提交返回首次结果并标记为重复。
 * 并发安全：通过 ConcurrentHashMap.compute 原子化“检查-执行-登记”。
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final Map<String, Object> results = new ConcurrentHashMap<>();

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
     * 若首次执行抛异常，则不登记（允许后续以同一幂等键重试）。
     */
    @SuppressWarnings("unchecked")
    public <T> IdempotentResult<T> execute(String scope, String idempotencyKey, Supplier<T> supplier) {
        String key = scope + "::" + idempotencyKey;
        AtomicBoolean executed = new AtomicBoolean(false);
        Object computed = ((ConcurrentHashMap<String, Object>) results).compute(key, (k, prev) -> {
            if (prev != null) {
                return prev;
            }
            executed.set(true);
            log.info("[idempotency] 首次执行: scope={}, key={}", scope, idempotencyKey);
            return supplier.get();
        });
        if (executed.get()) {
            return new IdempotentResult<>((T) computed, false);
        }
        log.info("[idempotency] 重复提交命中: scope={}, key={}, 返回首次结果", scope, idempotencyKey);
        return new IdempotentResult<>((T) computed, true);
    }
}
