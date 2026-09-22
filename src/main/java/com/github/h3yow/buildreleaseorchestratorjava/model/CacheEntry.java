package com.github.h3yow.buildreleaseorchestratorjava.model;

import java.time.Instant;

public class CacheEntry {

    private final String cacheKey;
    private final String pipelineId;
    private final String buildInputHash;
    private final String compatibilityTag;
    private final String checksum;
    private final String payload;
    private final Instant createdAt;
    private volatile Instant lastAccessAt;

    public CacheEntry(String cacheKey, String pipelineId, String buildInputHash,
                      String compatibilityTag, String checksum, String payload,
                      Instant createdAt, Instant lastAccessAt) {
        this.cacheKey = cacheKey;
        this.pipelineId = pipelineId;
        this.buildInputHash = buildInputHash;
        this.compatibilityTag = compatibilityTag;
        this.checksum = checksum;
        this.payload = payload;
        this.createdAt = createdAt;
        this.lastAccessAt = lastAccessAt;
    }

    public String getCacheKey() {
        return cacheKey;
    }

    public String getPipelineId() {
        return pipelineId;
    }

    public String getBuildInputHash() {
        return buildInputHash;
    }

    public String getCompatibilityTag() {
        return compatibilityTag;
    }

    public String getChecksum() {
        return checksum;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastAccessAt() {
        return lastAccessAt;
    }

    public void setLastAccessAt(Instant lastAccessAt) {
        this.lastAccessAt = lastAccessAt;
    }
}
