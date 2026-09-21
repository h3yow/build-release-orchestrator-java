package com.github.h3yow.buildreleaseorchestratorjava.tests;

import java.time.Instant;
import java.util.List;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.domain.PipelineRun;
import com.github.h3yow.buildreleaseorchestratorjava.domain.RunStatus;
import com.github.h3yow.buildreleaseorchestratorjava.domain.StageResult;
import com.github.h3yow.buildreleaseorchestratorjava.domain.StageStatus;
import com.github.h3yow.buildreleaseorchestratorjava.exception.OrchestratorException;
import com.github.h3yow.buildreleaseorchestratorjava.service.ArtifactService;
import com.github.h3yow.buildreleaseorchestratorjava.service.BuildCacheService;
import com.github.h3yow.buildreleaseorchestratorjava.service.IdempotencyService;
import com.github.h3yow.buildreleaseorchestratorjava.service.PipelineService;
import com.github.h3yow.buildreleaseorchestratorjava.service.ReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败重试：失败即停并保留阶段状态；重试从失败点继续，已成功阶段不重复执行、不重复计时。
 */
class RetryTest {

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
    void failureStopsPipelineAndRetryResumesFromFailedStage() {
        String pipelineId = "p-retry";
        System.out.printf("[test] 输入: pipeline=%s, failAtStage=TEST (一次性注入)%n", pipelineId);

        PipelineRun failed = pipelineService.trigger(pipelineId, "key-retry", "TEST").getRun();
        assertEquals(RunStatus.FAILED, failed.getStatus());
        assertNotNull(failed.getFailureReason());
        System.out.printf("[test] 失败后阶段状态: %s%n", stageSummary(failed));

        // 失败即停：TEST 失败，PACKAGE/PUBLISH 不得执行
        assertStage(failed, "CHECKOUT", StageStatus.SUCCESS);
        assertStage(failed, "BUILD", StageStatus.SUCCESS);
        assertStage(failed, "TEST", StageStatus.FAILED);
        assertStage(failed, "PACKAGE", StageStatus.PENDING);
        assertStage(failed, "PUBLISH", StageStatus.PENDING);
        assertNull(failed.getProducedVersion(), "失败不得产生版本");
        assertTrue(artifactService.list(pipelineId).isEmpty(), "失败不得留下制品(半更新状态)");
        assertNull(releaseService.currentVersion(pipelineId), "失败不得推进发布指针");

        // 记录已成功阶段的计时信息
        Instant checkoutStartedAt = stageOf(failed, "CHECKOUT").getStartedAt();
        long checkoutDuration = stageOf(failed, "CHECKOUT").getDurationMillis();
        Instant buildStartedAt = stageOf(failed, "BUILD").getStartedAt();
        long buildDuration = stageOf(failed, "BUILD").getDurationMillis();

        PipelineRun retried = pipelineService.retry(failed.getRunId());
        System.out.printf("[test] 重试后阶段状态: %s%n", stageSummary(retried));

        assertEquals(RunStatus.SUCCESS, retried.getStatus());
        // 已成功阶段不得被重复执行或重复计时
        assertEquals(checkoutStartedAt, stageOf(retried, "CHECKOUT").getStartedAt(), "CHECKOUT 不得重复计时");
        assertEquals(checkoutDuration, stageOf(retried, "CHECKOUT").getDurationMillis());
        assertEquals(buildStartedAt, stageOf(retried, "BUILD").getStartedAt(), "BUILD 不得重复计时");
        assertEquals(buildDuration, stageOf(retried, "BUILD").getDurationMillis());
        // 失败点及之后阶段被重新执行并成功
        assertStage(retried, "TEST", StageStatus.SUCCESS);
        assertStage(retried, "PACKAGE", StageStatus.SUCCESS);
        assertStage(retried, "PUBLISH", StageStatus.SUCCESS);
        // 断点续跑只推进一次版本
        assertEquals("1.0.1", retried.getProducedVersion());
        assertEquals(1, artifactService.list(pipelineId).size());
        assertEquals("1.0.1", releaseService.currentVersion(pipelineId));
    }

    @Test
    void retryOfNonFailedRunIsRejected() {
        String pipelineId = "p-retry-reject";
        PipelineRun success = pipelineService.trigger(pipelineId, "key-ok", null).getRun();
        System.out.printf("[test] 输入: 对成功运行 runId=%s 发起重试%n", success.getRunId());

        OrchestratorException ex = assertThrows(OrchestratorException.class,
                () -> pipelineService.retry(success.getRunId()));
        System.out.printf("[test] 判定依据: code=%s, message=%s%n", ex.getCode(), ex.getMessage());
        assertEquals("RUN_NOT_FAILED", ex.getCode());
    }

    @Test
    void failureAtPublishLeavesNoPartialState() {
        String pipelineId = "p-retry-publish";
        System.out.printf("[test] 输入: pipeline=%s, failAtStage=PUBLISH%n", pipelineId);

        PipelineRun failed = pipelineService.trigger(pipelineId, "key-pub", "PUBLISH").getRun();
        assertEquals(RunStatus.FAILED, failed.getStatus());
        assertStage(failed, "PUBLISH", StageStatus.FAILED);
        System.out.printf("[test] 判定依据: 制品数=%d, 当前版本=%s%n",
                artifactService.list(pipelineId).size(), releaseService.currentVersion(pipelineId));
        assertTrue(artifactService.list(pipelineId).isEmpty(), "PUBLISH 失败不得留下制品");
        assertNull(releaseService.currentVersion(pipelineId), "PUBLISH 失败不得推进发布指针");

        PipelineRun retried = pipelineService.retry(failed.getRunId());
        assertEquals(RunStatus.SUCCESS, retried.getStatus());
        assertEquals("1.0.1", retried.getProducedVersion(), "重试后版本不得跳变");
    }

    private StageResult stageOf(PipelineRun run, String name) {
        return run.getStages().stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
    }

    private void assertStage(PipelineRun run, String name, StageStatus expected) {
        assertEquals(expected, stageOf(run, name).getStatus(), "阶段 " + name + " 状态不符");
    }

    private String stageSummary(PipelineRun run) {
        List<String> parts = run.getStages().stream()
                .map(s -> s.getName() + "=" + s.getStatus() + "(" + s.getDurationMillis() + "ms)")
                .toList();
        return String.join(" -> ", parts);
    }
}
