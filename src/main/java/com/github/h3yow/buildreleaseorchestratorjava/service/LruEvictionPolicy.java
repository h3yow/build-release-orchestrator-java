package com.github.h3yow.buildreleaseorchestratorjava.service;

import java.util.Collection;

/**
 * 最近最少使用淘汰：选择最近访问时间最早（lastAccessNanos 最小）的条目。
 */
public class LruEvictionPolicy implements CacheEvictionPolicy {

    @Override
    public String name() {
        return "LRU";
    }

    @Override
    public String selectEvictionKey(Collection<BuildCacheService.CacheEntryView> entries) {
        BuildCacheService.CacheEntryView lru = null;
        for (BuildCacheService.CacheEntryView entry : entries) {
            if (lru == null
                    || entry.getLastAccessNanos() < lru.getLastAccessNanos()
                    || (entry.getLastAccessNanos() == lru.getLastAccessNanos()
                        && entry.getKey().compareTo(lru.getKey()) < 0)) {
                lru = entry;
            }
        }
        return lru == null ? null : lru.getKey();
    }
}
