package com.github.h3yow.buildreleaseorchestratorjava.service;

/**
 * 缓存淘汰策略：从当前条目中选出应被淘汰的 key。FIFO 按写入顺序，LRU 按最近访问顺序。
 */
public interface CacheEvictionPolicy {

    /** 策略名称（大写），用于配置解析与统计展示。 */
    String name();

    /**
     * 从当前条目中选出一个应被淘汰的 key。
     *
     * @param entries 只读的当前条目视图
     * @return 应淘汰条目的 key
     */
    String selectEvictionKey(java.util.Collection<BuildCacheService.CacheEntryView> entries);
}
