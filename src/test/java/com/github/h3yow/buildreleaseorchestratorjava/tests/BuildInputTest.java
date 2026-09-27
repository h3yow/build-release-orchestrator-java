package com.github.h3yow.buildreleaseorchestratorjava.tests;

import java.util.List;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.domain.Artifact;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 构建输入：触发携带 sourceRef/sourceContent 构成构建身份。
 * 输入不变复用缓存（制品内容与校验和一致），输入变化判定不兼容并重建，
 * 未命中原因（从未构建/损坏/不兼容）从正常触发路径的运行记录可观察。
 */
class BuildInputTest {

    private PipelineService pipelineService;
    private ArtifactService artifactService;
    private BuildCacheService buildCacheService;

    @BeforeEach
    void setUp() {
        OrchestratorProperties props = new OrchestratorProperties();
        artifactService = new ArtifactService(props);
        ReleaseService releaseService = new ReleaseService(artifactService);
        releaseService.wireCurrentVersionProvider();
        buildCacheService = new BuildCacheService(props);
        pipelineService = new PipelineService(new IdempotencyService(), artifactService,
                buildCacheService, releaseService, props);
    }

    @Test
    void identicalInputsReuseBuildAndProduceIdenticalArtifact() {
        String p = "p-input-same";
        System.out.println("[test] 输入: 两次触发携带相同 sourceRef=git://repo#main, sourceContent=content-A");

        PipelineService.TriggerResult r1 = pipelineService.trigger(p, "k1", null, "git://repo#main", "content-A");
        PipelineService.TriggerResult r2 = pipelineService.trigger(p, "k2", null, "git://repo#main", "content-A");

        List<Artifact> artifacts = artifactService.list(p);
        System.out.printf("[test] 判定依据: 首次cacheReason=%s, 再次cacheHit=%s/cacheReason=%s, 校验和一致=%s%n",
                r1.getRun().getCacheReason(), r2.getRun().getCacheHit(), r2.getRun().getCacheReason(),
                artifacts.get(0).getChecksum().equals(artifacts.get(1).getChecksum()));

        assertEquals(RunStatus.SUCCESS, r1.getRun().getStatus());
        assertEquals("MISS_NO_ENTRY", r1.getRun().getCacheReason(), "首次构建应判定为从未构建过");
        assertTrue(r2.getRun().getCacheHit(), "完全相同的输入必须复用缓存，不重复构建");
        assertEquals("HIT", r2.getRun().getCacheReason());
        assertEquals(2, artifacts.size());
        assertEquals(artifacts.get(0).getContent(), artifacts.get(1).getContent(), "制品内容必须一致");
        assertEquals(artifacts.get(0).getChecksum(), artifacts.get(1).getChecksum(), "制品校验和必须一致");
    }

    @Test
    void changedContentIsIncompatibleAndRebuilt() {
        String p = "p-input-changed";
        System.out.println("[test] 输入: 先构建 content-A，再以相同 sourceRef 不同 sourceContent=content-B 触发");

        pipelineService.trigger(p, "k1", null, "git://repo#main", "content-A");
        PipelineService.TriggerResult r2 = pipelineService.trigger(p, "k2", null, "git://repo#main", "content-B");

        List<Artifact> artifacts = artifactService.list(p);
        System.out.printf("[test] 判定依据: cacheHit=%s, cacheReason=%s, 新旧校验和不同=%s%n",
                r2.getRun().getCacheHit(), r2.getRun().getCacheReason(),
                !artifacts.get(0).getChecksum().equals(artifacts.get(1).getChecksum()));

        assertFalse(r2.getRun().getCacheHit(), "输入变化不得复用旧结果");
        assertEquals("MISS_INCOMPATIBLE", r2.getRun().getCacheReason(), "应判定为与当前输入不兼容");
        assertNotEquals(artifacts.get(0).getChecksum(), artifacts.get(1).getChecksum(), "重建后制品校验和必须不同");

        // 重建写回后，相同的新输入再次触发应命中
        PipelineService.TriggerResult r3 = pipelineService.trigger(p, "k3", null, "git://repo#main", "content-B");
        assertTrue(r3.getRun().getCacheHit(), "重建写回后相同输入应命中");
    }

    @Test
    void changedSourceRefAloneIsIncompatible() {
        String p = "p-input-ref";
        System.out.println("[test] 输入: 内容相同但 sourceRef 从 main 变为 feature-x");

        pipelineService.trigger(p, "k1", null, "git://repo#main", "content-A");
        PipelineService.TriggerResult r2 = pipelineService.trigger(p, "k2", null, "git://repo#feature-x", "content-A");

        System.out.printf("[test] 判定依据: cacheHit=%s, cacheReason=%s%n",
                r2.getRun().getCacheHit(), r2.getRun().getCacheReason());
        assertFalse(r2.getRun().getCacheHit(), "源码标识变化同样不得复用");
        assertEquals("MISS_INCOMPATIBLE", r2.getRun().getCacheReason());
    }

    @Test
    void corruptedCacheIsDetectedThroughTriggerPath() {
        String p = "p-input-corrupt";
        System.out.println("[test] 输入: 正常构建后篡改缓存，再以相同输入触发");

        pipelineService.trigger(p, "k1", null, "git://repo#main", "content-A");
        buildCacheService.simulateCorruption(p + "::build");
        PipelineService.TriggerResult r2 = pipelineService.trigger(p, "k2", null, "git://repo#main", "content-A");

        System.out.printf("[test] 判定依据: cacheHit=%s, cacheReason=%s%n",
                r2.getRun().getCacheHit(), r2.getRun().getCacheReason());
        assertFalse(r2.getRun().getCacheHit(), "损坏缓存不得被静默复用");
        assertEquals("MISS_CORRUPTED", r2.getRun().getCacheReason(), "应判定为缓存内容已损坏");
        assertEquals(RunStatus.SUCCESS, r2.getRun().getStatus(), "识别损坏后必须重建并成功");

        // 重建写回后再次触发应命中
        PipelineService.TriggerResult r3 = pipelineService.trigger(p, "k3", null, "git://repo#main", "content-A");
        assertTrue(r3.getRun().getCacheHit());
    }
}
