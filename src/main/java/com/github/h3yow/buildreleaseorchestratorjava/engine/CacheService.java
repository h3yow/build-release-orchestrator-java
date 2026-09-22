package com.github.h3yow.buildreleaseorchestratorjava.engine;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.error.ErrorCode;
import com.github.h3yow.buildreleaseorchestratorjava.error.OrchestratorException;
import com.github.h3yow.buildreleaseorchestratorjava.model.CacheEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Local build cache.
 *
 * <p>A cache key is derived from pipeline id + build input hash. On lookup the
 * service distinguishes:
 * <ul>
 *   <li>{@code MISS} – nothing cached, build required;</li>
 *   <li>{@code INCOMPATIBLE} – entry was produced by a different builder
 *       compatibility tag, rebuild required;</li>
 *   <li>{@code CORRUPTED} – the stored payload no longer matches its checksum,
 *       entry is removed and a rebuild happens (never silently reused);</li>
 *   <li>{@code HIT} – verified, reusable.</li>
 * </ul>
 * Capacity is bounded by {@code orchestrator.max-cache-entries} with a
 * configurable policy {@code LRU} (default) or {@code FIFO}; all mutation is
 * serialized by a single lock so eviction is concurrency safe.
 */
@Component
public class CacheService {

    public record LookupResult(boolean hit, CacheEntry entry, String reason) {
    }

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final OrchestratorProperties properties;
    private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public CacheService(OrchestratorProperties properties) {
        this.properties = properties;
    }

    public static String keyOf(String pipelineId, String buildInputHash) {
        return pipelineId + ":" + buildInputHash;
    }

    public LookupResult lookup(String pipelineId, String buildInputHash, String compatibilityTag) {
        String key = keyOf(pipelineId, buildInputHash);
        lock.lock();
        try {
            CacheEntry entry = entries.get(key);
            if (entry == null) {
                log.info("cache lookup pipeline={} buildInputHash={} result=MISS reason=no-entry",
                        pipelineId, buildInputHash);
                return new LookupResult(false, null, "MISS:no-entry");
            }
            if (!entry.getChecksum().equals(Checksums.sha256(entry.getPayload()))) {
                entries.remove(key);
                log.warn("cache lookup pipeline={} buildInputHash={} result=CORRUPTED reason=checksum-mismatch -> evicted, rebuild required",
                        pipelineId, buildInputHash);
                return new LookupResult(false, null, "CORRUPTED:checksum-mismatch");
            }
            if (!entry.getBuildInputHash().equals(buildInputHash)) {
                log.info("cache lookup pipeline={} buildInputHash={} result=MISS reason=key-mismatch",
                        pipelineId, buildInputHash);
                return new LookupResult(false, null, "MISS:key-mismatch");
            }
            if (!entry.getCompatibilityTag().equals(normalizedTag(compatibilityTag))) {
                log.info("cache lookup pipeline={} buildInputHash={} result=INCOMPATIBLE cachedTag={} currentTag={}",
                        pipelineId, buildInputHash, entry.getCompatibilityTag(), compatibilityTag);
                return new LookupResult(false, entry,
                        "INCOMPATIBLE:tag " + entry.getCompatibilityTag()
                                + " != " + normalizedTag(compatibilityTag));
            }
            entry.setLastAccessAt(Instant.now());
            log.info("cache lookup pipeline={} buildInputHash={} result=HIT checksum={}",
                    pipelineId, buildInputHash, entry.getChecksum());
            return new LookupResult(true, entry, "HIT:verified");
        } finally {
            lock.unlock();
        }
    }

    private static String normalizedTag(String tag) {
        return tag == null ? "" : tag;
    }

    public void put(String pipelineId, String buildInputHash, String compatibilityTag,
                    String payload, String checksum) {
        String key = keyOf(pipelineId, buildInputHash);
        String tag = normalizedTag(compatibilityTag);
        lock.lock();
        try {
            if (!checksum.equals(Checksums.sha256(payload))) {
                throw new OrchestratorException(ErrorCode.ARTIFACT_CHECKSUM_MISMATCH,
                        "refusing to cache pipeline " + pipelineId
                                + ": payload does not match checksum");
            }
            if (!entries.containsKey(key) && entries.size() >= properties.getMaxCacheEntries()) {
                evictOne(key);
            }
            if (!entries.containsKey(key) && entries.size() >= properties.getMaxCacheEntries()) {
                throw new OrchestratorException(ErrorCode.CACHE_LIMIT_EXCEEDED,
                        "cache capacity " + properties.getMaxCacheEntries()
                                + " reached and no eviction possible");
            }
            Instant now = Instant.now();
            entries.put(key, new CacheEntry(key, pipelineId, buildInputHash, tag, checksum,
                    payload, now, now));
            log.info("cache put pipeline={} buildInputHash={} tag={} checksum={} size={}",
                    pipelineId, buildInputHash, compatibilityTag, checksum, entries.size());
        } finally {
            lock.unlock();
        }
    }

    private void evictOne(String incomingKey) {
        String policy = properties.getCacheEvictionPolicy() == null
                ? "LRU" : properties.getCacheEvictionPolicy().trim().toUpperCase();
        Comparator<CacheEntry> comparator = switch (policy) {
            case "FIFO" -> Comparator.comparing(CacheEntry::getCreatedAt);
            case "LRU" -> Comparator.comparing(CacheEntry::getLastAccessAt);
            default -> throw new OrchestratorException(ErrorCode.ILLEGAL_STATE,
                    "unknown cache eviction policy: " + policy);
        };
        entries.values().stream()
                .filter(e -> !e.getCacheKey().equals(incomingKey))
                .min(comparator)
                .ifPresent(victim -> {
                    entries.remove(victim.getCacheKey());
                    log.info("cache evicted key={} policy={} reason=capacity",
                            victim.getCacheKey(), policy);
                });
    }

    /**
     * Test/diagnostic hook: deliberately corrupt the cached payload so the next
     * lookup must classify it as CORRUPTED and rebuild.
     */
    public boolean debugCorrupt(String pipelineId, String buildInputHash) {
        String key = keyOf(pipelineId, buildInputHash);
        lock.lock();
        try {
            CacheEntry entry = entries.get(key);
            if (entry == null) {
                return false;
            }
            CacheEntry corrupted = new CacheEntry(entry.getCacheKey(), entry.getPipelineId(),
                    entry.getBuildInputHash(), entry.getCompatibilityTag(), entry.getChecksum(),
                    entry.getPayload() + "###corrupted###", entry.getCreatedAt(),
                    entry.getLastAccessAt());
            entries.put(key, corrupted);
            log.warn("cache debug-corrupt key={} reason=test-injection", key);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public List<CacheEntry> list(String pipelineId) {
        List<CacheEntry> result = new ArrayList<>();
        for (CacheEntry entry : entries.values()) {
            if (entry.getPipelineId().equals(pipelineId)) {
                result.add(entry);
            }
        }
        result.sort(Comparator.comparing(CacheEntry::getCreatedAt));
        return result;
    }

    public int size() {
        return entries.size();
    }
}
