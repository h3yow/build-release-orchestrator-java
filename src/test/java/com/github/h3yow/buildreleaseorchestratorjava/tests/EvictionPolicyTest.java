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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 淘汰策略：FIFO 与 LRU 语义可切换且行为可区分；无法识别的策略必须明确失败；
 * 并发读写下容量不得突破。
 */
class EvictionPolicyTest {

    private BuildCacheService cacheWith(String policy, int capacity) {
        OrchestratorProperties props = new OrchestratorProperties();
        props.setCacheEvictionPolicy(policy);
        props.setCacheCapacity(capacity);
        return new BuildCacheService(props);
    }

    @Test
    void fifoEvictsOldestInsertedEvenIfRecentlyUsed() {
        BuildCacheService cache = cacheWith("FIFO", 3);
        System.out.println("[test] 输入: policy=FIFO, capacity=3, 写入 k1,k2,k3，访问 k1 后写入 k4");

        cache.put("k1", "fp", "p1");
        cache.put("k2", "fp", "p2");
        cache.put("k3", "fp", "p3");
        assertTrue(cache.lookup("k1", "fp").isHit(), "前置条件: k1 最近被访问");
        cache.put("k4", "fp", "p4");

        boolean k1Present = cache.lookup("k1", "fp").isHit();
        boolean k2Present = cache.lookup("k2", "fp").isHit();
        System.out.printf("[test] 判定依据: k1存在=%s, k2存在=%s, size=%d, evictions=%d, policy=%s%n",
                k1Present, k2Present, cache.stats().getSize(),
                cache.stats().getEvictions(), cache.stats().getEvictionPolicy());

        assertFalse(k1Present, "FIFO 必须淘汰最早插入的 k1，即使它最近被访问过");
        assertTrue(k2Present, "FIFO 不得淘汰较晚插入的 k2");
        assertEquals(3, cache.stats().getSize(), "容量不得被突破");
        assertEquals(1, cache.stats().getEvictions(), "淘汰次数必须可查询");
        assertEquals("FIFO", cache.stats().getEvictionPolicy(), "当前策略必须可查询");
    }

    @Test
    void lruEvictsLeastRecentlyUsedInsteadOfOldestInserted() {
        BuildCacheService cache = cacheWith("LRU", 3);
        System.out.println("[test] 输入: policy=LRU, capacity=3, 写入 k1,k2,k3，访问 k1 后写入 k4");

        cache.put("k1", "fp", "p1");
        cache.put("k2", "fp", "p2");
        cache.put("k3", "fp", "p3");
        assertTrue(cache.lookup("k1", "fp").isHit(), "前置条件: k1 最近被访问");
        cache.put("k4", "fp", "p4");

        boolean k1Present = cache.lookup("k1", "fp").isHit();
        boolean k2Present = cache.lookup("k2", "fp").isHit();
        System.out.printf("[test] 判定依据: k1存在=%s, k2存在=%s, size=%d, evictions=%d%n",
                k1Present, k2Present, cache.stats().getSize(), cache.stats().getEvictions());

        assertTrue(k1Present, "LRU 必须保留最近访问的 k1");
        assertFalse(k2Present, "LRU 必须淘汰最久未使用的 k2");
        assertEquals(3, cache.stats().getSize());
        assertEquals(1, cache.stats().getEvictions());
        assertEquals("LRU", cache.stats().getEvictionPolicy());
    }

    @Test
    void unknownPolicyFailsFastWithExplicitReason() {
        System.out.println("[test] 输入: policy=RANDOM（无法识别）");
        OrchestratorException ex = assertThrows(OrchestratorException.class,
                () -> cacheWith("RANDOM", 3));
        System.out.printf("[test] 判定依据: code=%s, message=%s%n", ex.getCode(), ex.getMessage());
        assertEquals("INVALID_EVICTION_POLICY", ex.getCode(), "无法识别的策略必须明确失败");
        assertTrue(ex.getMessage().contains("RANDOM"), "失败原因必须指出无法识别的配置值");
    }

    @Test
    void blankPolicyAlsoFailsFast() {
        OrchestratorException ex = assertThrows(OrchestratorException.class,
                () -> cacheWith(" ", 3));
        assertEquals("INVALID_EVICTION_POLICY", ex.getCode(), "空白策略同样不得静默退回默认");
    }

    @Test
    void fifoCapacityNotExceededUnderConcurrency() throws Exception {
        BuildCacheService cache = cacheWith("FIFO", 5);
        int threads = 16;
        System.out.printf("[test] 输入: policy=FIFO, capacity=5, 并发线程数=%d 交替读写%n", threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                for (int j = 0; j < 50; j++) {
                    if ((idx + j) % 2 == 0) {
                        cache.put("k" + (j % 8), "fp", "payload-" + j);
                    } else {
                        cache.lookup("k" + (j % 8), "fp");
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
        System.out.printf("[test] 判定依据: size=%d, capacity=5, evictions=%d%n",
                stats.getSize(), stats.getEvictions());
        assertTrue(stats.getSize() <= 5, "并发下 FIFO 容量不得被突破");
        assertTrue(stats.getEvictions() > 0, "并发写入应发生淘汰且次数可查询");
    }
}
