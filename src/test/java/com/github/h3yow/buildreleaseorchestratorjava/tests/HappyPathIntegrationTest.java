package com.github.h3yow.buildreleaseorchestratorjava.tests;

import com.github.h3yow.buildreleaseorchestratorjava.engine.OrchestratorService;
import com.github.h3yow.buildreleaseorchestratorjava.model.Artifact;
import com.github.h3yow.buildreleaseorchestratorjava.model.PipelineRun;
import com.github.h3yow.buildreleaseorchestratorjava.model.ReleaseState;
import com.github.h3yow.buildreleaseorchestratorjava.model.RunStatus;
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
public class HappyPathIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HappyPathIntegrationTest.class);

    @Autowired
    private OrchestratorService service;

    @Test
    void successfulRunProducesVersionArtifactReleaseAndIdempotentReplay() {
        String pipelineId = "happy-pipeline";
        service.registerPipeline(pipelineId, "happy",
                List.of("CHECKOUT", "BUILD", "TEST", "PACKAGE", "RELEASE"));

        TriggerRequest request = new TriggerRequest();
        request.setIdempotencyKey("idem-happy-1");
        request.setStageDelayMillis(1L);

        log.info("INPUT trigger pipeline={} idempotencyKey={} requestedVersion={}",
                pipelineId, request.getIdempotencyKey(), request.getRequestedVersion());
        TriggerResult first = service.trigger(pipelineId, request);
        log.info("DECISION first outcome={} runId={} version={} status={}",
                first.getOutcome(), first.getRunId(), first.getVersion(), first.getRunStatus());

        assertEquals(TriggerOutcome.STARTED, first.getOutcome());
        assertEquals(RunStatus.SUCCEEDED, first.getRunStatus());
        assertEquals("1.0.1", first.getVersion());
        assertNotNull(first.getRunId());

        PipelineRun run = service.getRun(first.getRunId());
        run.getStages().values().forEach(stage -> {
            assertEquals(StageStatus.SUCCEEDED, stage.getStatus(),
                    "stage " + stage.getStage() + " should succeed");
            assertTrue(stage.getDurationMillis() >= 0,
                    "stage " + stage.getStage() + " should record duration");
        });
        assertEquals(Boolean.FALSE, run.getStages().values().stream()
                .filter(s -> s.getStage().name().equals("BUILD"))
                .findFirst().orElseThrow().getCacheHit(), "first build should be a cache miss");

        List<Artifact> artifacts = service.listArtifacts(pipelineId);
        assertEquals(1, artifacts.size());
        Artifact artifact = artifacts.get(0);
        assertEquals("1.0.1", artifact.getVersion());
        assertEquals(first.getRunId(), artifact.getSourceRunId());
        assertEquals(run.getArtifactChecksum(), artifact.getChecksum());
        log.info("VERIFY artifact version={} checksum={} sourceRun={} buildInputHash={}",
                artifact.getVersion(), artifact.getChecksum(),
                artifact.getSourceRunId(), artifact.getBuildInputHash());

        ReleaseState release = service.getRelease(pipelineId);
        assertEquals("1.0.1", release.getCurrentVersion());
        assertEquals(artifact.getChecksum(), release.getCurrentChecksum());
        assertNull(release.getPreviousVersion());

        TriggerResult replay = service.trigger(pipelineId, request);
        log.info("DECISION replay outcome={} runId={} version={} (expect same run, DUPLICATE_REPLAY)",
                replay.getOutcome(), replay.getRunId(), replay.getVersion());
        assertEquals(TriggerOutcome.DUPLICATE_REPLAY, replay.getOutcome());
        assertEquals(first.getRunId(), replay.getRunId());
        assertEquals(1, service.listRuns(pipelineId).size(), "no duplicate run");
        assertEquals(1, service.listArtifacts(pipelineId).size(), "no duplicate artifact");
    }
}
