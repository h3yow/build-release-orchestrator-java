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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等触发：同一幂等键重复/并发提交只产生一次运行、一个制品、一次版本推进。
 */
class IdempotencyTriggerTest {

    private PipelineService pipelineService;
    private ArtifactService artifactService;

    @BeforeEach
    void setUp() {
        OrchestratorProperties props = new OrchestratorProperties();
        artifactService = new ArtifactService(props);
        ReleaseService releaseService = new ReleaseService(artifactService);
        releaseService.wireCurrentVersionProvider();
        pipelineService = new PipelineService(new IdempotencyService(), artifactService,
                new BuildCacheService(props), releaseService, props);
    }

    @Test
    void duplicateTriggerReturnsSameRunAndNoDuplicateArtifact() {
        String pipelineId = "p-idem";
        String key = "key-1";
        System.out.printf("[test] 输入: pipeline=%s, idempotencyKey=%s, 连续触发两次%n", pipelineId, key);

        PipelineService.TriggerResult first = pipelineService.trigger(pipelineId, key, null);
        PipelineService.TriggerResult second = pipelineService.trigger(pipelineId, key, null);

        System.out.printf("[test] 判定依据: first.runId=%s duplicated=%s, second.runId=%s duplicated=%s, 制品数=%d%n",
                first.getRun().getRunId(), first.isDuplicated(),
                second.getRun().getRunId(), second.isDuplicated(),
                artifactService.list(pipelineId).size());

        assertFalse(first.isDuplicated(), "首次触发不应标记为重复");
        assertTrue(second.isDuplicated(), "重复触发必须可区分(duplicated=true)");
        assertEquals(first.getRun().getRunId(), second.getRun().getRunId(), "重复触发必须返回同一运行");
        assertEquals(RunStatus.SUCCESS, second.getRun().getStatus());
        assertEquals(1, artifactService.list(pipelineId).size(), "不得产生重复制品");
        assertEquals("1.0.1", first.getRun().getProducedVersion(), "版本只推进一次");
    }

    @Test
    void concurrentDuplicateTriggerProducesSingleRun() throws Exception {
        String pipelineId = "p-idem-concurrent";
        String key = "key-concurrent";
        int threads = 8;
        System.out.printf("[test] 输入: pipeline=%s, idempotencyKey=%s, 并发线程数=%d%n", pipelineId, key, threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PipelineService.TriggerResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return pipelineService.trigger(pipelineId, key, null);
            }));
        }
        ready.await();
        start.countDown();

        List<String> runIds = new ArrayList<>();
        int duplicatedCount = 0;
        for (Future<PipelineService.TriggerResult> f : futures) {
            PipelineService.TriggerResult r = f.get();
            runIds.add(r.getRun().getRunId());
            if (r.isDuplicated()) {
                duplicatedCount++;
            }
        }
        pool.shutdown();

        long distinctRuns = runIds.stream().distinct().count();
        System.out.printf("[test] 判定依据: distinctRunIds=%d, duplicatedCount=%d, 制品数=%d%n",
                distinctRuns, duplicatedCount, artifactService.list(pipelineId).size());

        assertEquals(1, distinctRuns, "并发重复触发不得产生重复运行");
        assertEquals(threads - 1, duplicatedCount, "除首次外其余都必须标记为重复");
        assertEquals(1, artifactService.list(pipelineId).size(), "不得产生重复制品");
        assertEquals(1, pipelineService.listRuns().size(), "运行记录不得重复");
    }

    @Test
    void distinctKeysProduceDistinctRunsAndMonotonicVersions() {
        String pipelineId = "p-idem-distinct";
        System.out.printf("[test] 输入: pipeline=%s, 三个不同幂等键%n", pipelineId);

        PipelineService.TriggerResult r1 = pipelineService.trigger(pipelineId, "k1", null);
        PipelineService.TriggerResult r2 = pipelineService.trigger(pipelineId, "k2", null);
        PipelineService.TriggerResult r3 = pipelineService.trigger(pipelineId, "k3", null);

        System.out.printf("[test] 判定依据: versions=%s,%s,%s%n",
                r1.getRun().getProducedVersion(), r2.getRun().getProducedVersion(), r3.getRun().getProducedVersion());

        assertEquals("1.0.1", r1.getRun().getProducedVersion());
        assertEquals("1.0.2", r2.getRun().getProducedVersion());
        assertEquals("1.0.3", r3.getRun().getProducedVersion());
        assertEquals(3, artifactService.list(pipelineId).size());
    }
}
