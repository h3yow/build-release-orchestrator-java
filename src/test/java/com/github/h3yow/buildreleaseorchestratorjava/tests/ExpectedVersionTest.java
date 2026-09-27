package com.github.h3yow.buildreleaseorchestratorjava.tests;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.domain.BuildInput;
import com.github.h3yow.buildreleaseorchestratorjava.domain.RunStatus;
import com.github.h3yow.buildreleaseorchestratorjava.exception.OrchestratorException;
import com.github.h3yow.buildreleaseorchestratorjava.exception.VersionConflictException;
import com.github.h3yow.buildreleaseorchestratorjava.service.ArtifactService;
import com.github.h3yow.buildreleaseorchestratorjava.service.BuildCacheService;
import com.github.h3yow.buildreleaseorchestratorjava.service.IdempotencyService;
import com.github.h3yow.buildreleaseorchestratorjava.service.PipelineService;
import com.github.h3yow.buildreleaseorchestratorjava.service.ReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 触发指定期望版本：版本冲突拒绝并区分原因；
 * 拒绝时不消耗版本号、不覆盖已有制品、不移动当前发布指向、不留半更新状态。
 */
class ExpectedVersionTest {

    private static final String PIPELINE = "p-explicit";

    private PipelineService pipelineService;
    private ArtifactService artifactService;
    private ReleaseService releaseService;

    @BeforeEach
    void setUp() {
        OrchestratorProperties props = new OrchestratorProperties();
        artifactService = new ArtifactService(props);
        releaseService = new ReleaseService(artifactService);
        releaseService.wireCurrentVersionProvider();
        pipelineService = new PipelineService(new IdempotencyService(), artifactService,
                new BuildCacheService(props), releaseService, props);
    }

    private PipelineService.TriggerResult trigger(String key, String ref, String content, String expectedVersion) {
        return pipelineService.trigger(PIPELINE, key, null, new BuildInput(ref, content), expectedVersion);
    }

    @Test
    void explicitVersionIsUsedWhenFree() {
        System.out.println("[test] 输入: 首次触发指定期望版本 1.0.5");
        PipelineService.TriggerResult result = trigger("k1", "ref-a", "src-v1", "1.0.5");
        assertEquals("1.0.5", result.getRun().getProducedVersion());
        assertEquals("1.0.5", releaseService.currentVersion(PIPELINE));
        // 后续自动版本号从 1.0.6 继续，不回退、不冲突
        PipelineService.TriggerResult next = trigger("k2", "ref-b", "src-v2", null);
        System.out.printf("[test] 判定依据: 指定版本=%s, 随后自动版本=%s%n",
                result.getRun().getProducedVersion(), next.getRun().getProducedVersion());
        assertEquals("1.0.6", next.getRun().getProducedVersion());
    }

    @Test
    void conflictSameContentRejectedWithoutConsumingVersion() {
        trigger("k1", "ref-a", "src-v1", "1.0.1");
        System.out.println("[test] 输入: 再次指定 1.0.1 且输入完全相同(内容一致)");

        VersionConflictException ex = assertThrows(VersionConflictException.class,
                () -> trigger("k2", "ref-a", "src-v1", "1.0.1"));
        System.out.printf("[test] 判定依据: code=%s, message=%s%n", ex.getCode(), ex.getMessage());
        assertEquals(VersionConflictException.VERSION_ALREADY_EXISTS, ex.getCode());

        // 版本号不被额外消耗：下一个自动版本仍是 1.0.2
        PipelineService.TriggerResult next = trigger("k3", "ref-b", "src-v2", null);
        System.out.printf("[test] 判定依据: 冲突后自动版本=%s, 制品数=%d, 当前发布=%s%n",
                next.getRun().getProducedVersion(), artifactService.list(PIPELINE).size(),
                releaseService.currentVersion(PIPELINE));
        assertEquals("1.0.2", next.getRun().getProducedVersion(), "冲突拒绝不得消耗版本号");
        assertEquals(2, artifactService.list(PIPELINE).size(), "冲突拒绝不得新增或覆盖制品");
        // 已有制品内容不被覆盖，发布指针不移动
        assertEquals("artifact:compiled:src-v1", artifactService.get(PIPELINE, "1.0.1").getContent());
        assertEquals("1.0.2", releaseService.currentVersion(PIPELINE), "拒绝请求本身不得移动发布指向");
    }

    @Test
    void conflictDifferentContentRejectedAsChecksumMismatch() {
        trigger("k1", "ref-a", "src-v1", "1.0.1");
        String releaseBefore = releaseService.currentVersion(PIPELINE);
        System.out.println("[test] 输入: 再次指定 1.0.1 但内容变化(src-v2)");

        VersionConflictException ex = assertThrows(VersionConflictException.class,
                () -> trigger("k2", "ref-a", "src-v2", "1.0.1"));
        System.out.printf("[test] 判定依据: code=%s, message=%s%n", ex.getCode(), ex.getMessage());
        assertEquals(VersionConflictException.CHECKSUM_MISMATCH, ex.getCode());

        // 原制品不被覆盖、发布指向不变、运行失败但无半更新
        assertEquals("artifact:compiled:src-v1", artifactService.get(PIPELINE, "1.0.1").getContent(),
                "内容不一致时必须拒绝且不得覆盖已有制品");
        assertEquals(releaseBefore, releaseService.currentVersion(PIPELINE), "发布指针不得移动");
        assertEquals(1, artifactService.list(PIPELINE).size(), "不得留下半更新制品");

        PipelineService.TriggerResult next = trigger("k3", "ref-b", "src-v3", null);
        assertEquals("1.0.2", next.getRun().getProducedVersion(), "冲突拒绝不得消耗版本号");
    }

    @Test
    void invalidVersionFormatRejectedBeforeRunStarts() {
        long runsBefore = pipelineService.listRuns().size();
        System.out.println("[test] 输入: 非法期望版本 2.0.0");
        OrchestratorException ex = assertThrows(OrchestratorException.class,
                () -> trigger("k-bad", "ref-a", "src-v1", "2.0.0"));
        System.out.printf("[test] 判定依据: code=%s, message=%s%n", ex.getCode(), ex.getMessage());
        assertEquals("INVALID_VERSION", ex.getCode());
        assertEquals(runsBefore, pipelineService.listRuns().size(), "非法版本必须在产生运行前拒绝");
        assertNull(releaseService.currentVersion(PIPELINE));
        assertEquals(RunStatus.SUCCESS, trigger("k-ok", "ref-a", "src-v1", null).getRun().getStatus());
    }
}
