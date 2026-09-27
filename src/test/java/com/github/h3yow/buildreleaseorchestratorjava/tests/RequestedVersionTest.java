package com.github.h3yow.buildreleaseorchestratorjava.tests;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.domain.PipelineRun;
import com.github.h3yow.buildreleaseorchestratorjava.domain.RunStatus;
import com.github.h3yow.buildreleaseorchestratorjava.exception.VersionConflictException;
import com.github.h3yow.buildreleaseorchestratorjava.service.ArtifactService;
import com.github.h3yow.buildreleaseorchestratorjava.service.BuildCacheService;
import com.github.h3yow.buildreleaseorchestratorjava.service.IdempotencyService;
import com.github.h3yow.buildreleaseorchestratorjava.service.PipelineService;
import com.github.h3yow.buildreleaseorchestratorjava.service.ReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指定版本：触发可携带 requestedVersion；同名冲突按原因区分并拒绝，
 * 拒绝后不消耗版本号、不覆盖制品、不移动发布指向、不留半更新状态。
 */
class RequestedVersionTest {

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

    @Test
    void specifiedVersionIsUsedWithoutConsumingAutoCounter() {
        String p = "p-req";
        System.out.println("[test] 输入: 触发指定 requestedVersion=2.0.0");

        PipelineService.TriggerResult r = pipelineService.trigger(p, "k1", null, null, null, "2.0.0");

        System.out.printf("[test] 判定依据: producedVersion=%s, nextVersion=%s, currentRelease=%s%n",
                r.getRun().getProducedVersion(), artifactService.nextVersion(p), releaseService.currentVersion(p));
        assertEquals("2.0.0", r.getRun().getProducedVersion(), "必须产出指定版本");
        assertEquals("1.0.1", artifactService.nextVersion(p), "指定版本不得消耗自动版本号");
        assertEquals("2.0.0", releaseService.currentVersion(p), "发布指针应指向指定版本");
    }

    @Test
    void conflictWithIdenticalContentRejectedWithoutSideEffects() {
        String p = "p-req-same";
        pipelineService.trigger(p, "k1", null, "git://repo#main", "content-A", "2.0.0");
        String releaseBefore = releaseService.currentVersion(p);
        String nextBefore = artifactService.nextVersion(p);
        int artifactsBefore = artifactService.list(p).size();
        System.out.printf("[test] 输入: 以相同输入再次指定 version=2.0.0 (release=%s, next=%s)%n",
                releaseBefore, nextBefore);

        VersionConflictException ex = assertThrows(VersionConflictException.class,
                () -> pipelineService.trigger(p, "k2", null, "git://repo#main", "content-A", "2.0.0"));

        System.out.printf("[test] 判定依据: code=%s, release=%s, next=%s, 制品数=%d%n",
                ex.getCode(), releaseService.currentVersion(p), artifactService.nextVersion(p),
                artifactService.list(p).size());
        assertEquals(VersionConflictException.VERSION_ALREADY_EXISTS, ex.getCode(),
                "同名且内容一致必须判定为 VERSION_ALREADY_EXISTS");
        assertEquals(releaseBefore, releaseService.currentVersion(p), "发布指向不得移动");
        assertEquals(nextBefore, artifactService.nextVersion(p), "版本号不得被消耗");
        assertEquals(artifactsBefore, artifactService.list(p).size(), "不得新增或覆盖制品");
    }

    @Test
    void conflictWithDifferentContentRejectedAsChecksumMismatch() {
        String p = "p-req-diff";
        pipelineService.trigger(p, "k1", null, "git://repo#main", "content-A", "2.0.0");
        String originalContent = artifactService.get(p, "2.0.0").getContent();
        System.out.println("[test] 输入: 相同 version=2.0.0 但 sourceContent 变为 content-B");

        VersionConflictException ex = assertThrows(VersionConflictException.class,
                () -> pipelineService.trigger(p, "k2", null, "git://repo#main", "content-B", "2.0.0"));

        PipelineRun failedRun = pipelineService.listRuns().get(1);
        System.out.printf("[test] 判定依据: code=%s, 原制品内容未变=%s, release=%s, 失败运行可查=%s%n",
                ex.getCode(), artifactService.get(p, "2.0.0").getContent().equals(originalContent),
                releaseService.currentVersion(p), failedRun.getStatus());
        assertEquals(VersionConflictException.CHECKSUM_MISMATCH, ex.getCode(),
                "同名但内容不一致必须判定为 CHECKSUM_MISMATCH");
        assertEquals(originalContent, artifactService.get(p, "2.0.0").getContent(), "已有制品不得被覆盖");
        assertEquals("2.0.0", releaseService.currentVersion(p), "发布指向不得移动");
        assertEquals("1.0.1", artifactService.nextVersion(p), "版本号不得被消耗");
        assertEquals(RunStatus.FAILED, failedRun.getStatus(), "被拒绝的运行应标记失败且可查询");
        assertTrue(failedRun.getFailureReason().contains("CHECKSUM_MISMATCH"), "失败原因必须可区分");
    }

    @Test
    void autoVersionStaysMonotonicAfterRejection() {
        String p = "p-req-monotonic";
        pipelineService.trigger(p, "k1", null, null, null, "2.0.0");
        assertThrows(VersionConflictException.class,
                () -> pipelineService.trigger(p, "k2", null, null, null, "2.0.0"));
        System.out.println("[test] 输入: 指定版本冲突被拒绝后，再普通触发一次");

        PipelineService.TriggerResult r = pipelineService.trigger(p, "k3", null);

        System.out.printf("[test] 判定依据: producedVersion=%s, 制品数=%d%n",
                r.getRun().getProducedVersion(), artifactService.list(p).size());
        assertEquals("1.0.1", r.getRun().getProducedVersion(),
                "被拒绝的触发不得消耗版本号，自动版本必须从 1.0.1 开始");
        assertEquals(2, artifactService.list(p).size(), "只应有 2.0.0 与 1.0.1 两个制品");
        assertEquals("1.0.1", releaseService.currentVersion(p));
    }
}
