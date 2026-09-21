package com.github.h3yow.buildreleaseorchestratorjava.service;

import com.github.h3yow.buildreleaseorchestratorjava.domain.CacheLookupResult;
import org.springframework.stereotype.Service;

/**
 * 构建缓存：命中/失效原因可解释，损坏或不兼容必须识别并重建，淘汰策略可配置且并发安全。
 */
@Service
public class BuildCacheService {

    /**
     * 查询缓存：命中返回校验和；未命中给出原因（无条目/损坏/不兼容）。
     */
    public CacheLookupResult lookup(String cacheKey, String expectedFingerprint) {
        // TODO: 实现
        return null;
    }

    /**
     * 写入缓存并按容量与策略淘汰。
     */
    public void put(String cacheKey, String fingerprint, String payload) {
        // TODO: 实现
    }

    /**
     * 统计信息：条目数、命中、未命中、淘汰次数。
     */
    public CacheStats stats() {
        // TODO: 实现
        return null;
    }

    public static class CacheStats {
        private final int size;
        private final long hits;
        private final long misses;
        private final long evictions;

        public CacheStats(int size, long hits, long misses, long evictions) {
            this.size = size;
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
        }

        public int getSize() { return size; }
        public long getHits() { return hits; }
        public long getMisses() { return misses; }
        public long getEvictions() { return evictions; }
    }
}
