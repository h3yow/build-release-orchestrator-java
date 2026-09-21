package com.github.h3yow.buildreleaseorchestratorjava.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.domain.CacheLookupResult;
import com.github.h3yow.buildreleaseorchestratorjava.service.BuildCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 构建缓存：命中/失效原因可解释，损坏或不兼容必须识别并重建，LRU 淘汰并发安全。
 */
class BuildCacheTest {

    private OrchestratorProperties props;
    private BuildCacheService cache;

    @BeforeEach
    void setUp() {
        props = new OrchestratorProperties();
        props.setCacheCapacity(3);
        cache = new BuildCacheService(props);
    }

    @Test
    void missThenHitWithExplainableReasons() {
        System.out.println("[test] 输入: key=k1, fingerprint=fp1, 首次查询后写入再查询");
        CacheLookupResult miss = cache.lookup("k1", "fp1");
        assertFalse(miss.isHit());
        assertEquals(CacheLookupResult.Reason.MISS_NO_ENTRY, miss.getReason());

        cache.put("k1", "fp1", "payload-1");
        CacheLookupResult hit = cache.lookup("k1", "fp1");
        System.out.printf("[test] 判定依据: miss.reason=%s, hit=%s, hit.checksum=%s%n",
                miss.getReason(), hit.isHit(), hit.getChecksum());
        assertTrue(hit.isHit());
        assertEquals(CacheLookupResult.Reason.HIT, hit.getReason());
        assertEquals(1, cache.stats().getHits());
        assertEquals(1, cache.stats().getMisses());
    }

    @Test
    void corruptedCacheIsDetectedAndNotReused() {
        cache.put("k1", "fp1", "payload-1");
        cache.simulateCorruption("k1");
        System.out.println("[test] 输入: key=k1 缓存被篡改后查询");

        CacheLookupResult result = cache.lookup("k1", "fp1");
        System.out.printf("[test] 判定依据: hit=%s, reason=%s, size=%d%n",
                result.isHit(), result.getReason(), cache.stats().getSize());
        assertFalse(result.isHit(), "损坏缓存不得被静默复用");
        assertEquals(CacheLookupResult.Reason.MISS_CORRUPTED, result.getReason());
        assertEquals(0, cache.stats().getSize(), "损坏条目必须被移除以便重建");

        // 重建后可再次命中
        cache.put("k1", "fp1", "payload-1");
        assertTrue(cache.lookup("k1", "fp1").isHit());
    }

    @Test
    void incompatibleFingerprintIsDetectedAndRebuilt() {
        cache.put("k1", "fp-old", "payload-1");
        System.out.println("[test] 输入: key=k1, 缓存指纹=fp-old, 当前指纹=fp-new");

        CacheLookupResult result = cache.lookup("k1", "fp-new");
        System.out.printf("[test] 判定依据: hit=%s, reason=%s%n", result.isHit(), result.getReason());
        assertFalse(result.isHit());
        assertEquals(CacheLookupResult.Reason.MISS_INCOMPATIBLE, result.getReason());
        assertEquals(0, cache.stats().getSize(), "不兼容条目必须被移除");
    }

    @Test
    void lruEvictionRespectsCapacity() {
        System.out.println("[test] 输入: capacity=3, 依次写入 k1,k2,k3，访问 k1 后写入 k4");
        cache.put("k1", "fp", "p1");
        cache.put("k2", "fp", "p2");
        cache.put("k3", "fp", "p3");
        cache.lookup("k1", "fp"); // k1 变为最近使用
        cache.put("k4", "fp", "p4"); // 应淘汰最久未使用的 k2

        System.out.printf("[test] 判定依据: size=%d, evictions=%d, k1命中=%s, k2命中=%s%n",
                cache.stats().getSize(), cache.stats().getEvictions(),
                cache.lookup("k1", "fp").isHit(), cache.lookup("k2", "fp").isHit());
        assertEquals(3, cache.stats().getSize());
        assertEquals(1, cache.stats().getEvictions());
        // 注意：上面两次 lookup 会影响统计，这里只验证容量与淘汰语义
    }

    @Test
    void concurrentAccessIsConsistent() throws Exception {
        int threads = 16;
        System.out.printf("[test] 输入: 并发线程数=%d 对同一 key 交替读写%n", threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                for (int j = 0; j < 50; j++) {
                    if ((idx + j) % 2 == 0) {
                        cache.put("k" + (j % 5), "fp", "payload-" + j);
                    } else {
                        cache.lookup("k" + (j % 5), "fp");
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
        System.out.printf("[test] 判定依据: size=%d, capacity=%d, hits=%d, misses=%d, evictions=%d%n",
                stats.getSize(), props.getCacheCapacity(), stats.getHits(), stats.getMisses(), stats.getEvictions());
        assertTrue(stats.getSize() <= props.getCacheCapacity(), "并发下缓存容量不得超限");
        assertTrue(stats.getHits() + stats.getMisses() > 0);
    }
}
