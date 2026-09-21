package com.github.h3yow.buildreleaseorchestratorjava.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.domain.RunStatus;
import com.github.h3yow.buildreleaseorchestratorjava.service.ArtifactService;
import com.github.h3yow.buildreleaseorchestratorjava.service.BuildCacheService;
import com.github.h3yow.buildreleaseorchestratorjava.service.IdempotencyService;
import com.github.h3yow.buildreleaseorchestratorjava.service.PipelineService;
import com.github.h3yow.buildreleaseorchestratorjava.service.ReleaseService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发触发同一流水线：结果必须一致，无重复运行、无版本跳变、无半更新状态。
 */
class PipelineConcurrencyTest {

    @Test
    void concurrentTriggersWithDistinctKeysProduceConsistentResults() throws Exception {
        OrchestratorProperties props = new OrchestratorProperties();
        ArtifactService artifacts = new ArtifactService(props);
        ReleaseService release = new ReleaseService(artifacts);
        release.wireCurrentVersionProvider();
        PipelineService pipelines = new PipelineService(new IdempotencyService(), artifacts,
                new BuildCacheService(props), release, props);

        String pipelineId = "p-concurrent";
        int threads = 10;
        System.out.printf("[test] 输入: pipeline=%s, 并发线程数=%d, 各自不同幂等键%n", pipelineId, threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PipelineService.TriggerResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "ck-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return pipelines.trigger(pipelineId, key, null);
            }));
        }
        ready.await();
        start.countDown();
        List<PipelineService.TriggerResult> results = new ArrayList<>();
        for (Future<PipelineService.TriggerResult> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();

        List<String> versions = results.stream()
                .map(r -> r.getRun().getProducedVersion()).sorted().toList();
        long successCount = results.stream().filter(r -> r.getRun().getStatus() == RunStatus.SUCCESS).count();
        System.out.printf("[test] 判定依据: 成功数=%d, versions=%s, 制品数=%d, 当前版本=%s%n",
                successCount, versions, artifacts.list(pipelineId).size(), release.currentVersion(pipelineId));

        assertEquals(threads, successCount, "所有并发触发都必须成功");
        assertEquals(threads, versions.stream().distinct().count(), "版本必须唯一，不得跳变");
        for (int i = 1; i <= threads; i++) {
            assertTrue(versions.contains("1.0." + i), "缺少版本 1.0." + i);
        }
        assertEquals(threads, artifacts.list(pipelineId).size(), "制品数与运行数必须一致");
        assertEquals(threads, pipelines.listRuns().size(), "不得有重复或丢失的运行记录");
        assertEquals("1.0." + threads, release.currentVersion(pipelineId), "发布指针必须指向最后版本");
    }
}
