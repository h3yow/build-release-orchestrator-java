package com.github.h3yow.buildreleaseorchestratorjava.tests;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.domain.Artifact;
import com.github.h3yow.buildreleaseorchestratorjava.exception.LimitExceededException;
import com.github.h3yow.buildreleaseorchestratorjava.service.ArtifactService;
import com.github.h3yow.buildreleaseorchestratorjava.service.BuildCacheService;
import com.github.h3yow.buildreleaseorchestratorjava.service.IdempotencyService;
import com.github.h3yow.buildreleaseorchestratorjava.service.PipelineService;
import com.github.h3yow.buildreleaseorchestratorjava.service.ReleaseService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可配置上限：运行数量、制品保留量与缓存容量超限按策略淘汰或拒绝，不残留部分状态。
 */
class LimitsTest {

    @Test
    void maxRunsEvictsOldestFinishedRun() {
        OrchestratorProperties props = new OrchestratorProperties();
        props.setMaxRuns(3);
        ArtifactService artifacts = new ArtifactService(props);
        ReleaseService release = new ReleaseService(artifacts);
        release.wireCurrentVersionProvider();
        PipelineService pipelines = new PipelineService(new IdempotencyService(), artifacts,
                new BuildCacheService(props), release, props);

        System.out.println("[test] 输入: maxRuns=3, 连续触发 5 次不同幂等键");
        for (int i = 1; i <= 5; i++) {
            pipelines.trigger("p-limit-run", "k" + i, null);
        }
        System.out.printf("[test] 判定依据: 运行记录数=%d, maxRuns=%d%n",
                pipelines.listRuns().size(), props.getMaxRuns());
        assertEquals(3, pipelines.listRuns().size(), "超限必须淘汰最早已完成运行");
    }

    @Test
    void maxArtifactsEvictsOldestButProtectsCurrentRelease() {
        OrchestratorProperties props = new OrchestratorProperties();
        props.setMaxArtifactsPerPipeline(3);
        ArtifactService artifacts = new ArtifactService(props);
        ReleaseService release = new ReleaseService(artifacts);
        release.wireCurrentVersionProvider();
        PipelineService pipelines = new PipelineService(new IdempotencyService(), artifacts,
                new BuildCacheService(props), release, props);

        String pipelineId = "p-limit-artifact";
        System.out.println("[test] 输入: maxArtifacts=3, 发布 5 个版本后回滚到最旧存活版本");
        for (int i = 1; i <= 5; i++) {
            pipelines.trigger(pipelineId, "k" + i, null);
        }
        System.out.printf("[test] 判定依据: 制品数=%d, 当前版本=%s, 存活版本=%s%n",
                artifacts.list(pipelineId).size(), release.currentVersion(pipelineId),
                artifacts.list(pipelineId).stream().map(Artifact::getVersion).toList());
        assertEquals(3, artifacts.list(pipelineId).size(), "超保留量必须淘汰最旧制品");
        assertEquals("1.0.5", release.currentVersion(pipelineId));
        // 回滚到 1.0.3 后，继续发布会保护 1.0.3 不被淘汰
        release.rollback(pipelineId, "1.0.3");
        pipelines.trigger(pipelineId, "k6", null);
        assertTrue(artifacts.list(pipelineId).stream().anyMatch(a -> a.getVersion().equals("1.0.3")),
                "当前发布版本必须被保留策略保护");
    }

    @Test
    void artifactLimitRejectsWhenNothingEvictable() {
        OrchestratorProperties props = new OrchestratorProperties();
        props.setMaxArtifactsPerPipeline(1);
        ArtifactService artifacts = new ArtifactService(props);
        ReleaseService release = new ReleaseService(artifacts);
        release.wireCurrentVersionProvider();

        String pipelineId = "p-limit-reject";
        artifacts.register(pipelineId, "1.0.1", "c1", "test://m");
        release.release(pipelineId, "1.0.1"); // 唯一制品被保护
        System.out.println("[test] 输入: maxArtifacts=1, 当前发布=1.0.1(受保护), 再注册 1.0.2");

        LimitExceededException ex = assertThrows(LimitExceededException.class,
                () -> artifacts.register(pipelineId, "1.0.2", "c2", "test://m"));
        System.out.printf("[test] 判定依据: code=%s, 制品数=%d%n", ex.getCode(), artifacts.list(pipelineId).size());
        assertEquals(1, artifacts.list(pipelineId).size(), "拒绝后不得残留部分状态");
    }

    @Test
    void cacheCapacityEvictsByLru() {
        OrchestratorProperties props = new OrchestratorProperties();
        props.setCacheCapacity(2);
        BuildCacheService cache = new BuildCacheService(props);

        System.out.println("[test] 输入: cacheCapacity=2, 写入 a,b 后访问 a，再写入 c");
        cache.put("a", "fp", "pa");
        cache.put("b", "fp", "pb");
        cache.lookup("a", "fp");
        cache.put("c", "fp", "pc");

        BuildCacheService.CacheStats stats = cache.stats();
        System.out.printf("[test] 判定依据: size=%d, evictions=%d, a命中=%s, b命中=%s%n",
                stats.getSize(), stats.getEvictions(),
                cache.lookup("a", "fp").isHit(), cache.lookup("b", "fp").isHit());
        assertEquals(2, stats.getSize(), "缓存容量不得超限");
        assertEquals(1, stats.getEvictions());
    }
}
