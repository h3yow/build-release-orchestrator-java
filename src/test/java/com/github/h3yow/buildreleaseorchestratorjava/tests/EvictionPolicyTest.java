package com.github.h3yow.buildreleaseorchestratorjava.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.exception.OrchestratorException;
import com.github.h3yow.buildreleaseorchestratorjava.service.BuildCacheService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缓存淘汰策略：FIFO 与 LRU 语义差异、未知策略必须明确拒绝（启动配置与运行时切换）、
 * 条目数与淘汰次数可查询、并发下容量不被突破。
 */
class EvictionPolicyTest {

    private BuildCacheService cacheWith(String policy, int capacity) {
        OrchestratorProperties props = new OrchestratorProperties();
        props.setCacheCapacity(capacity);
        props.setCacheEvictionPolicy(policy);
        return new BuildCacheService(props);
    }

    @Test
    void fifoEvictsEarliestInsertedRegardlessOfAccess() {
        BuildCacheService cache = cacheWith("FIFO", 3);
        System.out.println("[test] 输入: policy=FIFO, capacity=3, 写入 k1,k2,k3 后访问 k1，再写入 k4");
        cache.put("k1", "fp", "p1");
        cache.put("k2", "fp", "p2");
        cache.put("k3", "fp", "p3");
        cache.lookup("k1", "fp"); // FIFO 下访问不改变淘汰顺序
        cache.put("k4", "fp", "p4");

        BuildCacheService.CacheStats stats = cache.stats();
        boolean k1Alive = cache.lookup("k1", "fp").isHit();
        boolean k2Alive = cache.lookup("k2", "fp").isHit();
        System.out.printf("[test] 判定依据: policy=%s, size=%d, evictions=%d, k1存活=%s, k2存活=%s%n",
                stats.getPolicy(), stats.getSize(), stats.getEvictions(), k1Alive, k2Alive);
        assertEquals("FIFO", stats.getPolicy());
        assertEquals(3, stats.getSize(), "容量不得被突破");
        assertEquals(1, stats.getEvictions(), "淘汰次数必须可查询");
        assertTrue(!k1Alive, "FIFO 必须淘汰最早写入的 k1，即使刚访问过");
        assertTrue(k2Alive, "FIFO 不得淘汰较晚写入的 k2");
    }

    @Test
    void lruEvictsLeastRecentlyUsed() {
        BuildCacheService cache = cacheWith("LRU", 3);
        System.out.println("[test] 输入: policy=LRU, capacity=3, 写入 k1,k2,k3 后访问 k1，再写入 k4");
        cache.put("k1", "fp", "p1");
        cache.put("k2", "fp", "p2");
        cache.put("k3", "fp", "p3");
        cache.lookup("k1", "fp"); // k1 变为最近使用
        cache.put("k4", "fp", "p4");

        BuildCacheService.CacheStats stats = cache.stats();
        boolean k1Alive = cache.lookup("k1", "fp").isHit();
        boolean k2Alive = cache.lookup("k2", "fp").isHit();
        System.out.printf("[test] 判定依据: policy=%s, size=%d, evictions=%d, k1存活=%s, k2存活=%s%n",
                stats.getPolicy(), stats.getSize(), stats.getEvictions(), k1Alive, k2Alive);
        assertEquals("LRU", stats.getPolicy());
        assertEquals(3, stats.getSize());
        assertEquals(1, stats.getEvictions());
        assertTrue(k1Alive, "LRU 必须保留最近访问的 k1");
        assertTrue(!k2Alive, "LRU 必须淘汰最久未使用的 k2");
    }

    @Test
    void unknownPolicyIsRejectedAtConstruction() {
        OrchestratorProperties props = new OrchestratorProperties();
        props.setCacheEvictionPolicy("RANDOM");
        System.out.println("[test] 输入: 启动配置 orchestrator.cache-eviction-policy=RANDOM");
        OrchestratorException ex = assertThrows(OrchestratorException.class,
                () -> new BuildCacheService(props));
        System.out.printf("[test] 判定依据: code=%s, message=%s%n", ex.getCode(), ex.getMessage());
        assertEquals("UNKNOWN_EVICTION_POLICY", ex.getCode(), "未知策略必须明确失败，不得静默退回默认策略");
    }

    @Test
    void unknownRuntimeSwitchIsRejectedAndKeepsOldPolicy() {
        BuildCacheService cache = cacheWith("FIFO", 3);
        System.out.println("[test] 输入: 当前策略=FIFO, 请求切换为 WEIRD");
        OrchestratorException ex = assertThrows(OrchestratorException.class,
                () -> cache.setEvictionPolicy("WEIRD"));
        System.out.printf("[test] 判定依据: code=%s, 当前策略仍为=%s%n", ex.getCode(), cache.stats().getPolicy());
        assertEquals("UNKNOWN_EVICTION_POLICY", ex.getCode());
        assertEquals("FIFO", cache.stats().getPolicy(), "切换失败后原策略必须保持不变");
    }

    @Test
    void runtimeSwitchChangesEvictionSemantics() throws Exception {
        BuildCacheService cache = cacheWith("LRU", 3);
        cache.setEvictionPolicy("FIFO");
        System.out.println("[test] 输入: 运行时 LRU -> FIFO 后执行 FIFO 淘汰序列并并发读写");
        assertEquals("FIFO", cache.stats().getPolicy());

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                for (int j = 0; j < 100; j++) {
                    String key = "k" + ((idx + j) % 8);
                    if ((idx + j) % 3 == 0) {
                        cache.lookup(key, "fp");
                    } else {
                        cache.put(key, "fp", "payload-" + j);
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        BuildCacheService.CacheStats stats = cache.stats();
        System.out.printf("[test] 判定依据: policy=%s, size=%d, capacity=3, evictions=%d%n",
                stats.getPolicy(), stats.getSize(), stats.getEvictions());
        assertEquals("FIFO", stats.getPolicy());
        assertTrue(stats.getSize() <= 3, "并发读写容量不得被突破");
        assertTrue(stats.getEvictions() > 0, "并发写入应产生可查询的淘汰计数");
    }
}
