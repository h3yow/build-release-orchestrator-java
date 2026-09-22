package com.github.h3yow.buildreleaseorchestratorjava.tests;

import com.github.h3yow.buildreleaseorchestratorjava.engine.OrchestratorService;
import com.github.h3yow.buildreleaseorchestratorjava.model.Artifact;
import com.github.h3yow.buildreleaseorchestratorjava.model.ReleaseState;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "orchestrator.max-runs-per-pipeline=3",
        "orchestrator.max-artifacts-per-pipeline=2",
        "orchestrator.max-cache-entries=2"
})
public class LimitsIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(LimitsIntegrationTest.class);

    @Autowired
    private OrchestratorService service;

    private TriggerRequest trigger(String idem, int index) {
        TriggerRequest request = new TriggerRequest();
        request.setIdempotencyKey(idem);
        request.setSources("limit-src-" + index);
        request.setCompatibilityTag("builder-1");
        request.setStageDelayMillis(1L);
        return request;
    }

    @Test
    void runLimitRejectsAndArtifactRetentionEvictsOldestProtectingRelease() {
        String pipelineId = "limits-pipeline";
        service.registerPipeline(pipelineId, "limits",
                List.of("CHECKOUT", "BUILD", "TEST", "PACKAGE", "RELEASE"));

        for (int i = 1; i <= 3; i++) {
            TriggerResult result = service.trigger(pipelineId, trigger("idem-limit-" + i, i));
            log.info("DECISION trigger #{} outcome={} version={}", i, result.getOutcome(),
                    result.getVersion());
            assertEquals(TriggerOutcome.STARTED, result.getOutcome());
        }
        assertEquals(3, service.listRuns(pipelineId).size());

        TriggerResult rejected = service.trigger(pipelineId, trigger("idem-limit-4", 4));
        log.info("DECISION trigger #4 outcome={} error={}", rejected.getOutcome(),
                rejected.getError() == null ? null : rejected.getError().code());
        assertEquals(TriggerOutcome.REJECTED_LIMIT, rejected.getOutcome());
        assertNotNull(rejected.getError());
        assertEquals(3, service.listRuns(pipelineId).size(), "rejected trigger adds no run");

        List<Artifact> artifacts = service.listArtifacts(pipelineId);
        log.info("DECISION retained artifacts after 3 releases, cap=2: {}",
                artifacts.stream().map(Artifact::getVersion).toList());
        assertEquals(2, artifacts.size(), "retention must not exceed cap");

        ReleaseState release = service.getRelease(pipelineId);
        log.info("DECISION release pointers current={} previous={}",
                release.getCurrentVersion(), release.getPreviousVersion());
        List<String> retainedVersions = artifacts.stream().map(Artifact::getVersion).toList();
        assertTrue(retainedVersions.contains(release.getCurrentVersion()),
                "current release artifact must be protected from eviction");
        assertTrue(retainedVersions.contains(release.getPreviousVersion()),
                "previous release artifact must be protected from eviction");
        assertFalse(retainedVersions.contains("1.0.1"),
                "the oldest unprotected artifact must have been evicted");
    }
}
