package com.github.h3yow.buildreleaseorchestratorjava.domain;

import com.github.h3yow.buildreleaseorchestratorjava.exception.OrchestratorException;

/**
 * 缓存淘汰策略：LRU(最近最少使用) / FIFO(先进先出)。
 * 无法识别的配置必须明确失败，不得静默退回默认策略。
 */
public enum EvictionPolicy {
    LRU,
    FIFO;

    public static EvictionPolicy parse(String value) {
        if (value != null) {
            String normalized = value.trim();
            for (EvictionPolicy policy : values()) {
                if (policy.name().equalsIgnoreCase(normalized)) {
                    return policy;
                }
            }
        }
        throw new OrchestratorException("INVALID_EVICTION_POLICY",
                "无法识别的缓存淘汰策略: " + value + ", 仅支持: LRU, FIFO");
    }
}
