package com.github.h3yow.buildreleaseorchestratorjava.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.domain.CacheLookupResult;
import com.github.h3yow.buildreleaseorchestratorjava.exception.OrchestratorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 构建缓存：命中/失效原因可解释，损坏或不兼容必须识别并重建，淘汰策略可切换且并发安全。
 * 支持 FIFO（先进先出）与 LRU（最近最少使用），无法识别的策略必须明确失败。
 */
@Service
public class BuildCacheService {

    private static final Logger log = LoggerFactory.getLogger(BuildCacheService.class);

    static final class CacheEntry {
        final String key;
        final String fingerprint;
        String payload;
        final String checksum;
        /** 写入序号：首次插入时分配；覆盖同 key 条目不重新分配，以严格保持 FIFO 入队顺序 */
        final long insertSeq;
        volatile long lastAccessNanos;

        CacheEntry(String key, String fingerprint, String payload, String checksum, long insertSeq) {
            this.key = key;
            this.fingerprint = fingerprint;
            this.payload = payload;
            this.checksum = checksum;
            this.insertSeq = insertSeq;
            this.lastAccessNanos = System.nanoTime();
        }
    }

    private final OrchestratorProperties properties;
    private final Map<String, CacheEntry> entries = new HashMap<>();
    private final Object lock = new Object();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong insertSequence = new AtomicLong();
    private volatile CacheEvictionPolicy evictionPolicy;

    public BuildCacheService(OrchestratorProperties properties) {
        this.properties = properties;
        this.evictionPolicy = resolvePolicy(properties.getCacheEvictionPolicy());
        log.info("[cache] 初始化淘汰策略: {}", evictionPolicy.name());
    }

    /**
     * 解析淘汰策略：仅接受 FIFO/LRU（大小写不敏感），无法识别必须明确失败，不得静默退回默认策略。
     */
    public static CacheEvictionPolicy resolvePolicy(String policy) {
        if (policy == null) {
            throw new OrchestratorException("UNKNOWN_EVICTION_POLICY",
                    "无法识别的缓存淘汰策略: null，支持的策略: FIFO, LRU");
        }
        String normalized = policy.trim().toUpperCase();
        return switch (normalized) {
            case "FIFO" -> new FifoEvictionPolicy();
            case "LRU" -> new LruEvictionPolicy();
            default -> throw new OrchestratorException("UNKNOWN_EVICTION_POLICY",
                    "无法识别的缓存淘汰策略: '" + policy + "'，支持的策略: FIFO, LRU");
        };
    }

    /**
     * 运行时切换淘汰策略：无法识别时明确失败并保持原策略不变。
     */
    public void setEvictionPolicy(String policy) {
        CacheEvictionPolicy resolved = resolvePolicy(policy);
        synchronized (lock) {
            log.info("[cache] 切换淘汰策略: {} -> {}", evictionPolicy.name(), resolved.name());
            this.evictionPolicy = resolved;
        }
    }

    public String currentPolicyName() {
        return evictionPolicy.name();
    }

    /**
     * 查询缓存：命中返回校验和与缓存产物；未命中给出原因（无条目/损坏/不兼容）。
     * 损坏或与当前构建指纹不兼容的条目会被识别并移除，不得静默复用。
     */
    public CacheLookupResult lookup(String cacheKey, String expectedFingerprint) {
        synchronized (lock) {
            CacheEntry entry = entries.get(cacheKey);
            if (entry == null) {
                misses.incrementAndGet();
                log.info("[cache] 未命中: key={}, 原因=无缓存条目(从未构建过)", cacheKey);
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
            return CacheLookupResult.hit(entry.checksum, entry.payload);
        }
    }

    /**
     * 写入缓存并按容量与当前策略淘汰。同 key 覆盖保留首次入队顺序（FIFO 语义）。
     */
    public void put(String cacheKey, String fingerprint, String payload) {
        synchronized (lock) {
            String checksum = ArtifactService.sha256(payload);
            CacheEntry existing = entries.get(cacheKey);
            long seq = existing != null ? existing.insertSeq : insertSequence.incrementAndGet();
            entries.put(cacheKey, new CacheEntry(cacheKey, fingerprint, payload, checksum, seq));
            log.info("[cache] 写入: key={}, fingerprint={}, checksum={}, insertSeq={}",
                    cacheKey, fingerprint, checksum, seq);
            evictIfNeeded();
        }
    }

    private void evictIfNeeded() {
        CacheEvictionPolicy policy = evictionPolicy;
        int capacity = properties.getCacheCapacity();
        while (entries.size() > capacity) {
            List<CacheEntryView> views = new ArrayList<>(entries.size());
            for (CacheEntry e : entries.values()) {
                views.add(new CacheEntryView(e.key, e.insertSeq, e.lastAccessNanos));
            }
            String victimKey = policy.selectEvictionKey(views);
            if (victimKey == null || entries.remove(victimKey) == null) {
                // 理论上不会发生：策略在非空集合上必须选出存在的 key
                throw new IllegalStateException("淘汰策略未选出有效条目: policy=" + policy.name());
            }
            evictions.incrementAndGet();
            log.info("[cache] 淘汰({}): key={}, capacity={}", policy.name(), victimKey, capacity);
        }
    }

    /**
     * 统计信息：条目数、命中、未命中、淘汰次数与当前策略。
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

    /** 淘汰策略使用的只读条目视图。 */
    public static final class CacheEntryView {
        private final String key;
        private final long insertSeq;
        private final long lastAccessNanos;

        public CacheEntryView(String key, long insertSeq, long lastAccessNanos) {
            this.key = key;
            this.insertSeq = insertSeq;
            this.lastAccessNanos = lastAccessNanos;
        }

        public String getKey() { return key; }
        public long getInsertSeq() { return insertSeq; }
        public long getLastAccessNanos() { return lastAccessNanos; }
    }

    public static class CacheStats {
        private final int size;
        private final long hits;
        private final long misses;
        private final long evictions;
        private final String policy;

        public CacheStats(int size, long hits, long misses, long evictions, String policy) {
            this.size = size;
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.policy = policy;
        }

        public int getSize() { return size; }
        public long getHits() { return hits; }
        public long getMisses() { return misses; }
        public long getEvictions() { return evictions; }
        public String getPolicy() { return policy; }
    }
}
