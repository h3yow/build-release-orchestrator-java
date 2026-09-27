package com.github.h3yow.buildreleaseorchestratorjava.tests;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.domain.Artifact;
import com.github.h3yow.buildreleaseorchestratorjava.domain.BuildInput;
import com.github.h3yow.buildreleaseorchestratorjava.domain.PipelineRun;
import com.github.h3yow.buildreleaseorchestratorjava.service.ArtifactService;
import com.github.h3yow.buildreleaseorchestratorjava.service.BuildCacheService;
import com.github.h3yow.buildreleaseorchestratorjava.service.IdempotencyService;
import com.github.h3yow.buildreleaseorchestratorjava.service.PipelineService;
import com.github.h3yow.buildreleaseorchestratorjava.service.ReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 构建输入身份：输入不变时复用既有构建结果（内容/校验和一致、不重建）；
 * 输入变化必须重建，且从正常触发路径（GET /runs 的 cacheReason）可区分三种未命中原因。
 */
class BuildInputReuseTest {

    private static final String PIPELINE = "p-input";

    private PipelineService pipelineService;
    private ArtifactService artifactService;
    private BuildCacheService cacheService;

    @BeforeEach
    void setUp() {
        OrchestratorProperties props = new OrchestratorProperties();
        artifactService = new ArtifactService(props);
        ReleaseService releaseService = new ReleaseService(artifactService);
        releaseService.wireCurrentVersionProvider();
        cacheService = new BuildCacheService(props);
        pipelineService = new PipelineService(new IdempotencyService(), artifactService,
                cacheService, releaseService, props);
    }

    private PipelineService.TriggerResult trigger(String key, String ref, String content) {
        System.out.printf("[test] 输入: key=%s, sourceRef=%s, sourceContent=%s%n", key, ref, content);
        return pipelineService.trigger(PIPELINE, key, null, new BuildInput(ref, content), null);
    }

    @Test
    void sameInputIsReusedWithoutRebuild() {
        PipelineRun first = trigger("k1", "ref-a", "src-v1").getRun();
        System.out.printf("[test] 首次: cacheHit=%s, reason=%s, reused=%s, version=%s%n",
                first.getCacheHit(), first.getCacheReason(), first.getReusedBuild(), first.getProducedVersion());
        assertFalse(first.getCacheHit(), "首次构建不得命中缓存");
        assertEquals("MISS_NO_ENTRY", first.getCacheReason());
        assertFalse(first.getReusedBuild());

        Artifact artifact1 = artifactService.get(PIPELINE, first.getProducedVersion());

        // 完全相同的输入再次触发：复用，不重建
        PipelineRun second = trigger("k2", "ref-a", "src-v1").getRun();
        System.out.printf("[test] 相同输入再触发: cacheHit=%s, reason=%s, reused=%s, version=%s%n",
                second.getCacheHit(), second.getCacheReason(), second.getReusedBuild(), second.getProducedVersion());
        assertTrue(second.getCacheHit(), "相同输入必须复用缓存");
        assertEquals("HIT", second.getCacheReason());
        assertTrue(second.getReusedBuild(), "必须标记为复用而非重新构建");

        // 最终制品内容与校验和与首次构建保持一致
        Artifact artifact2 = artifactService.get(PIPELINE, second.getProducedVersion());
        System.out.printf("[test] 判定依据: checksum1=%s, checksum2=%s, 内容一致=%s%n",
                artifact1.getChecksum(), artifact2.getChecksum(),
                artifact1.getContent().equals(artifact2.getContent()));
        assertEquals(artifact1.getChecksum(), artifact2.getChecksum(), "相同输入的制品校验和必须一致");
        assertEquals(artifact1.getContent(), artifact2.getContent(), "相同输入的制品内容必须逐字节一致");
        assertEquals(1, cacheService.stats().getHits());
    }

    @Test
    void changedContentRebuildsWithIncompatibleReason() {
        PipelineRun first = trigger("k1", "ref-a", "src-v1").getRun();
        Artifact artifact1 = artifactService.get(PIPELINE, first.getProducedVersion());

        // 同一源码标识、内容变化：不得复用，原因必须是“与当前输入不兼容”
        PipelineRun changed = trigger("k2", "ref-a", "src-v2").getRun();
        System.out.printf("[test] 判定依据: cacheHit=%s, reason=%s, reused=%s%n",
                changed.getCacheHit(), changed.getCacheReason(), changed.getReusedBuild());
        assertFalse(changed.getCacheHit(), "输入变化不得复用旧结果");
        assertEquals("MISS_INCOMPATIBLE", changed.getCacheReason(), "必须能看出是输入不兼容");
        assertFalse(changed.getReusedBuild(), "输入变化必须重新构建");

        Artifact artifact2 = artifactService.get(PIPELINE, changed.getProducedVersion());
        System.out.printf("[test] 判定依据: checksum1=%s, checksum2=%s%n",
                artifact1.getChecksum(), artifact2.getChecksum());
        assertFalse(artifact1.getChecksum().equals(artifact2.getChecksum()), "输入变化后制品校验和必须不同");

        // 重建已写回：用新内容再触发应命中
        PipelineRun again = trigger("k3", "ref-a", "src-v2").getRun();
        assertTrue(again.getCacheHit());
        assertEquals(artifact2.getChecksum(),
                artifactService.get(PIPELINE, again.getProducedVersion()).getChecksum());
    }

    @Test
    void neverBuiltSourceReportsMissNoEntry() {
        // 先构建 ref-a，再以全新 ref-b 触发：必须报告“从未构建过”，而不是不兼容
        trigger("k1", "ref-a", "src-v1");
        PipelineRun fresh = trigger("k2", "ref-b", "src-v1").getRun();
        System.out.printf("[test] 判定依据: 新源码标识 ref-b 的 cacheReason=%s%n", fresh.getCacheReason());
        assertFalse(fresh.getCacheHit());
        assertEquals("MISS_NO_ENTRY", fresh.getCacheReason(), "不同源码标识应表现为从未构建过");
    }

    @Test
    void corruptedCacheRebuildsWithCorruptedReason() {
        PipelineRun first = trigger("k1", "ref-a", "src-v1").getRun();
        String checksumBefore = artifactService.get(PIPELINE, first.getProducedVersion()).getChecksum();

        // 绕开缓存写入路径进行篡改（模拟磁盘/介质损坏），判定仍要从正常触发路径可见
        String cacheKey = PIPELINE + "::build::ref-a";
        cacheService.simulateCorruption(cacheKey);
        System.out.println("[test] 输入: 篡改 ref-a 缓存内容后再次正常触发");

        PipelineRun rebuilt = trigger("k2", "ref-a", "src-v1").getRun();
        System.out.printf("[test] 判定依据: cacheHit=%s, reason=%s%n",
                rebuilt.getCacheHit(), rebuilt.getCacheReason());
        assertFalse(rebuilt.getCacheHit());
        assertEquals("MISS_CORRUPTED", rebuilt.getCacheReason(), "必须能看出是缓存内容损坏");
        assertFalse(rebuilt.getReusedBuild());

        // 损坏后重建，制品恢复为与首次一致的内容与校验和
        Artifact artifactAfter = artifactService.get(PIPELINE, rebuilt.getProducedVersion());
        assertNotNull(artifactAfter);
        assertEquals(checksumBefore, artifactAfter.getChecksum(), "重建后内容与首次构建一致");

        // 已写回，再触发命中
        assertTrue(trigger("k3", "ref-a", "src-v1").getRun().getCacheHit());
    }
}
