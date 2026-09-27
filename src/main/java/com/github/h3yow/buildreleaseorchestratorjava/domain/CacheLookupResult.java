package com.github.h3yow.buildreleaseorchestratorjava.domain;

/**
 * 缓存查询结果：命中与否及可解释的原因。
 */
public class CacheLookupResult {
    public enum Reason {
        HIT,
        MISS_NO_ENTRY,
        MISS_CORRUPTED,
        MISS_INCOMPATIBLE
    }

    private final boolean hit;
    private final Reason reason;
    private final String checksum;
    /** 命中时回传缓存的构建产物内容，保证复用结果与首次构建逐字节一致；未命中为 null。 */
    private final String payload;

    private CacheLookupResult(boolean hit, Reason reason, String checksum, String payload) {
        this.hit = hit;
        this.reason = reason;
        this.checksum = checksum;
        this.payload = payload;
    }

    public static CacheLookupResult hit(String checksum, String payload) {
        return new CacheLookupResult(true, Reason.HIT, checksum, payload);
    }

    public static CacheLookupResult miss(Reason reason) {
        return new CacheLookupResult(false, reason, null, null);
    }

    public boolean isHit() { return hit; }
    public Reason getReason() { return reason; }
    public String getChecksum() { return checksum; }
    public String getPayload() { return payload; }
}
