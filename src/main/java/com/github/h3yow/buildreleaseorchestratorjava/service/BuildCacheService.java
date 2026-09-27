package com.github.h3yow.buildreleaseorchestratorjava.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.domain.CacheLookupResult;
import com.github.h3yow.buildreleaseorchestratorjava.domain.EvictionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 构建缓存：命中/失效原因可解释，损坏或不兼容必须识别并重建，淘汰策略可配置且并发安全。
 */
@Service
public class BuildCacheService {

    private static final Logger log = LoggerFactory.getLogger(BuildCacheService.class);

    private static final class CacheEntry {
        final String key;
        final String fingerprint;
        String payload;
        final String checksum;
        final Instant createdAt;
        /** 插入序号：FIFO 淘汰依据，单调递增 */
        final long insertionSeq;
        long lastAccessNanos;

        CacheEntry(String key, String fingerprint, String payload, String checksum, long insertionSeq) {
            this.key = key;
            this.fingerprint = fingerprint;
            this.payload = payload;
            this.checksum = checksum;
            this.createdAt = Instant.now();
            this.insertionSeq = insertionSeq;
            this.lastAccessNanos = System.nanoTime();
        }
    }

    private final OrchestratorProperties properties;
    /** 构造期解析并固定：无法识别的策略在此即失败，不允许静默退回默认 */
    private final EvictionPolicy evictionPolicy;
    private final Map<String, CacheEntry> entries = new HashMap<>();
    private final Object lock = new Object();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private long insertionCounter = 0;

    public BuildCacheService(OrchestratorProperties properties) {
        this.properties = properties;
        this.evictionPolicy = EvictionPolicy.parse(properties.getCacheEvictionPolicy());
        log.info("[cache] 淘汰策略: {}, 容量: {}", evictionPolicy, properties.getCacheCapacity());
    }

    /**
     * 查询缓存：命中返回校验和；未命中给出原因（无条目/损坏/不兼容）。
     * 损坏或与当前构建指纹不兼容的条目会被识别并移除，不得静默复用。
     */
    public CacheLookupResult lookup(String cacheKey, String expectedFingerprint) {
        synchronized (lock) {
            CacheEntry entry = entries.get(cacheKey);
            if (entry == null) {
                misses.incrementAndGet();
                log.info("[cache] 未命中: key={}, 原因=无缓存条目", cacheKey);
                return CacheLookupResult.miss(CacheLookupResult.Reason.MISS_NO_ENTRY);
            }
            String actualChecksum = ArtifactService.sha256(entry.payload);
            if (!actualChecksum.equals(entry.checksum)) {
                entries.remove(cacheKey);
                misses.incrementAndGet();
                log.warn("[cache] 未命中: key={}, 原因=缓存损坏(登记校验和={}, 实际={}), 已移除并需重建",
                        cacheKey, entry.checksum, actualChecksum);
                return CacheLookupResult.miss(CacheLookupResult.Reason.MISS_CORRUPTED);
            }
            if (!entry.fingerprint.equals(expectedFingerprint)) {
                entries.remove(cacheKey);
                misses.incrementAndGet();
                log.info("[cache] 未命中: key={}, 原因=构建指纹不兼容(缓存={}, 当前={}), 已移除并需重建",
                        cacheKey, entry.fingerprint, expectedFingerprint);
                return CacheLookupResult.miss(CacheLookupResult.Reason.MISS_INCOMPATIBLE);
            }
            entry.lastAccessNanos = System.nanoTime();
            hits.incrementAndGet();
            log.info("[cache] 命中: key={}, checksum={}", cacheKey, entry.checksum);
            return CacheLookupResult.hit(entry.checksum);
        }
    }

    /**
     * 写入缓存并按容量与策略淘汰（LRU/FIFO，构造期已校验）。
     */
    public void put(String cacheKey, String fingerprint, String payload) {
        synchronized (lock) {
            String checksum = ArtifactService.sha256(payload);
            entries.put(cacheKey, new CacheEntry(cacheKey, fingerprint, payload, checksum, ++insertionCounter));
            log.info("[cache] 写入: key={}, fingerprint={}, checksum={}", cacheKey, fingerprint, checksum);
            evictIfNeeded();
        }
    }

    private void evictIfNeeded() {
        int capacity = properties.getCacheCapacity();
        while (entries.size() > capacity) {
            CacheEntry victim = null;
            for (CacheEntry e : entries.values()) {
                if (victim == null || isOlder(e, victim)) {
                    victim = e;
                }
            }
            entries.remove(victim.key);
            evictions.incrementAndGet();
            log.info("[cache] 淘汰({}): key={}, capacity={}", evictionPolicy, victim.key, capacity);
        }
    }

    /** LRU 比较最近访问时间，FIFO 比较插入序号。 */
    private boolean isOlder(CacheEntry candidate, CacheEntry current) {
        return switch (evictionPolicy) {
            case LRU -> candidate.lastAccessNanos < current.lastAccessNanos;
            case FIFO -> candidate.insertionSeq < current.insertionSeq;
        };
    }

    /**
     * 统计信息：条目数、命中、未命中、淘汰次数与当前淘汰策略。
     */
    public CacheStats stats() {
        synchronized (lock) {
            return new CacheStats(entries.size(), hits.get(), misses.get(), evictions.get(), evictionPolicy.name());
        }
    }

    /**
     * 本地模拟钩子：篡改缓存内容以模拟缓存损坏（仅用于测试与本地验证）。
     */
    public void simulateCorruption(String cacheKey) {
        synchronized (lock) {
            CacheEntry entry = entries.get(cacheKey);
            if (entry != null) {
                entry.payload = entry.payload + "-corrupted";
                log.warn("[cache] 模拟缓存损坏: key={}", cacheKey);
            }
        }
    }

    public static class CacheStats {
        private final int size;
        private final long hits;
        private final long misses;
        private final long evictions;
        private final String evictionPolicy;

        public CacheStats(int size, long hits, long misses, long evictions, String evictionPolicy) {
            this.size = size;
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.evictionPolicy = evictionPolicy;
        }

        public int getSize() { return size; }
        public long getHits() { return hits; }
        public long getMisses() { return misses; }
        public long getEvictions() { return evictions; }
        public String getEvictionPolicy() { return evictionPolicy; }
    }
}
