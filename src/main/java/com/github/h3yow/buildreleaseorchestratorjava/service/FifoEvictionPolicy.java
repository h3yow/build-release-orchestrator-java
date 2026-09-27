package com.github.h3yow.buildreleaseorchestratorjava.service;

import java.util.Collection;

/**
 * 先进先出淘汰：选择最早写入（写入序号最小）的条目。
 */
public class FifoEvictionPolicy implements CacheEvictionPolicy {

    @Override
    public String name() {
        return "FIFO";
    }

    @Override
    public String selectEvictionKey(Collection<BuildCacheService.CacheEntryView> entries) {
        BuildCacheService.CacheEntryView oldest = null;
        for (BuildCacheService.CacheEntryView entry : entries) {
            if (oldest == null
                    || entry.getInsertSeq() < oldest.getInsertSeq()
                    || (entry.getInsertSeq() == oldest.getInsertSeq()
                        && entry.getKey().compareTo(oldest.getKey()) < 0)) {
                oldest = entry;
            }
        }
        return oldest == null ? null : oldest.getKey();
    }
}
