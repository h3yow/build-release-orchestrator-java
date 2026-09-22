package com.github.h3yow.buildreleaseorchestratorjava.tests;

import com.github.h3yow.buildreleaseorchestratorjava.engine.OrchestratorService;
import com.github.h3yow.buildreleaseorchestratorjava.model.PipelineRun;
import com.github.h3yow.buildreleaseorchestratorjava.model.RunStatus;
import com.github.h3yow.buildreleaseorchestratorjava.model.StageExecution;
import com.github.h3yow.buildreleaseorchestratorjava.model.StageName;
import com.github.h3yow.buildreleaseorchestratorjava.model.StageStatus;
import com.github.h3yow.buildreleaseorchestratorjava.model.TriggerOutcome;
import com.github.h3yow.buildreleaseorchestratorjava.model.TriggerResult;
import com.github.h3yow.buildreleaseorchestratorjava.web.TriggerRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class RetryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RetryIntegrationTest.class);

    @Autowired
    private OrchestratorService service;

    @Test
    void failedRunRetriesFromFailurePointWithoutReTimingSucceededStages() {
        String pipelineId = "retry-pipeline";
        service.registerPipeline(pipelineId, "retry",
                List.of("CHECKOUT", "BUILD", "TEST", "PACKAGE", "RELEASE"));

        TriggerRequest request = new TriggerRequest();
        request.setIdempotencyKey("idem-retry-1");
        request.setFailStage("TEST");
        request.setFailAttempt(1);
        request.setStageDelayMillis(1L);

        log.info("INPUT trigger pipeline={} idempotencyKey={} failStage=TEST failAttempt=1",
                pipelineId, request.getIdempotencyKey());
        TriggerResult first = service.trigger(pipelineId, request);
        assertEquals(TriggerOutcome.FAILED, first.getOutcome());
        assertEquals(RunStatus.FAILED, first.getRunStatus());
        assertNull(first.getVersion(), "no version consumed when failure is before PACKAGE");

        PipelineRun failed = service.getRun(first.getRunId());
        log.info("DECISION run failed at stage status={} reason={} attempt={}",
                failed.getStatus(), failed.getFailureReason(), failed.getAttempt());
        assertEquals(StageStatus.SUCCEEDED, failed.getStages().get(StageName.CHECKOUT).getStatus());
        assertEquals(StageStatus.SUCCEEDED, failed.getStages().get(StageName.BUILD).getStatus());
        assertEquals(StageStatus.FAILED, failed.getStages().get(StageName.TEST).getStatus());
        assertEquals(StageStatus.PENDING, failed.getStages().get(StageName.PACKAGE).getStatus());
        assertEquals(StageStatus.PENDING, failed.getStages().get(StageName.RELEASE).getStatus());
        assertTrue(failed.getFailureReason().contains("simulated test failure"));

        long checkoutDurationBefore = failed.getStages().get(StageName.CHECKOUT).getDurationMillis();
        long buildDurationBefore = failed.getStages().get(StageName.BUILD).getDurationMillis();
        assertNotNull(failed.getStages().get(StageName.BUILD).getCacheHit());

        log.info("INPUT retry runId={}", first.getRunId());
        TriggerResult retried = service.retry(first.getRunId());
        log.info("DECISION retry outcome={} status={} version={}",
                retried.getOutcome(), retried.getRunStatus(), retried.getVersion());
        assertEquals(TriggerOutcome.STARTED, retried.getOutcome());
        assertEquals(RunStatus.SUCCEEDED, retried.getRunStatus());
        assertEquals("1.0.1", retried.getVersion(), "version must not jump after a pre-package failure");

        PipelineRun succeeded = service.getRun(first.getRunId());
        assertEquals(2, succeeded.getAttempt());
        assertEquals(StageStatus.SUCCEEDED, succeeded.getStages().get(StageName.TEST).getStatus());
        assertEquals(StageStatus.SUCCEEDED, succeeded.getStages().get(StageName.PACKAGE).getStatus());
        assertEquals(StageStatus.SUCCEEDED, succeeded.getStages().get(StageName.RELEASE).getStatus());

        StageExecution checkout = succeeded.getStages().get(StageName.CHECKOUT);
        StageExecution build = succeeded.getStages().get(StageName.BUILD);
        assertEquals(checkoutDurationBefore, checkout.getDurationMillis(),
                "succeeded stage duration must not be re-timed on retry");
        assertEquals(buildDurationBefore, build.getDurationMillis(),
                "succeeded stage duration must not be re-timed on retry");

        StageExecution retriedBuild = succeeded.getStages().get(StageName.BUILD);
        log.info("VERIFY build stage not re-executed on retry: cacheHit={} cacheReason={}",
                retriedBuild.getCacheHit(), retriedBuild.getCacheReason());
        assertEquals(Boolean.FALSE, retriedBuild.getCacheHit(),
                "BUILD already succeeded on attempt 1 (fresh build); it is not re-executed, "
                        + "so its original cache-miss observation is preserved");
        assertTrue(retriedBuild.getCacheReason().startsWith("MISS"));
    }
}
