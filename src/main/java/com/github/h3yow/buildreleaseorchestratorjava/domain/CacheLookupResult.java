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

    private CacheLookupResult(boolean hit, Reason reason, String checksum) {
        this.hit = hit;
        this.reason = reason;
        this.checksum = checksum;
    }

    public static CacheLookupResult hit(String checksum) {
        return new CacheLookupResult(true, Reason.HIT, checksum);
    }

    public static CacheLookupResult miss(Reason reason) {
        return new CacheLookupResult(false, reason, null);
    }

    public boolean isHit() { return hit; }
    public Reason getReason() { return reason; }
    public String getChecksum() { return checksum; }
}
