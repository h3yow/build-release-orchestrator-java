package com.github.h3yow.buildreleaseorchestratorjava.tests;

import com.github.h3yow.buildreleaseorchestratorjava.engine.OrchestratorService;
import com.github.h3yow.buildreleaseorchestratorjava.model.PipelineRun;
import com.github.h3yow.buildreleaseorchestratorjava.model.StageExecution;
import com.github.h3yow.buildreleaseorchestratorjava.model.StageName;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "orchestrator.max-cache-entries=3",
        "orchestrator.cache-eviction-policy=LRU"
})
public class CacheIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CacheIntegrationTest.class);

    @Autowired
    private OrchestratorService service;

    private TriggerRequest request(String idem, String branch, String sources, String tag) {
        TriggerRequest request = new TriggerRequest();
        request.setIdempotencyKey(idem);
        request.setBranch(branch);
        request.setSources(sources);
        request.setCompatibilityTag(tag);
        request.setStageDelayMillis(1L);
        return request;
    }

    private StageExecution buildStage(PipelineRun run) {
        return run.getStages().get(StageName.BUILD);
    }

    @Test
    void cacheLifecycleHitCorruptionIncompatibleAndLruEviction() {
        String pipelineId = "cache-pipeline";
        service.registerPipeline(pipelineId, "cache",
                List.of("CHECKOUT", "BUILD", "TEST", "PACKAGE", "RELEASE"));

        TriggerResult first = service.trigger(pipelineId, request("idem-cache-1", "main", "src-A", "builder-1"));
        PipelineRun run1 = service.getRun(first.getRunId());
        log.info("DECISION run1 BUILD cacheHit={} reason={} buildInputHash={}",
                buildStage(run1).getCacheHit(), buildStage(run1).getCacheReason(),
                run1.getBuildInputHash());
        assertEquals(Boolean.FALSE, buildStage(run1).getCacheHit());
        assertTrue(buildStage(run1).getCacheReason().startsWith("MISS"));

        // Same inputs, different idempotency key -> second run must hit the cache.
        TriggerResult second = service.trigger(pipelineId, request("idem-cache-2", "main", "src-A", "builder-1"));
        PipelineRun run2 = service.getRun(second.getRunId());
        log.info("DECISION run2 BUILD cacheHit={} reason={}",
                buildStage(run2).getCacheHit(), buildStage(run2).getCacheReason());
        assertEquals(Boolean.TRUE, buildStage(run2).getCacheHit());
        assertEquals("HIT:verified", buildStage(run2).getCacheReason());

        // Different builder compatibility tag -> INCOMPATIBLE, must rebuild (not silently reuse).
        TriggerResult third = service.trigger(pipelineId, request("idem-cache-3", "main", "src-A", "builder-2"));
        PipelineRun run3 = service.getRun(third.getRunId());
        log.info("DECISION run3 BUILD cacheHit={} reason={}",
                buildStage(run3).getCacheHit(), buildStage(run3).getCacheReason());
        assertEquals(Boolean.FALSE, buildStage(run3).getCacheHit());
        assertTrue(buildStage(run3).getCacheReason().startsWith("INCOMPATIBLE"),
                "a different compatibility tag must be reported incompatible");

        // Corrupt the cache for src-A/builder-2 and rebuild with the same inputs.
        boolean corrupted = service.corruptCache(pipelineId, run3.getBuildInputHash());
        log.info("INPUT corrupt cache buildInputHash={} corrupted={}",
                run3.getBuildInputHash(), corrupted);
        assertTrue(corrupted);
        TriggerResult fourth = service.trigger(pipelineId, request("idem-cache-4", "main", "src-A", "builder-2"));
        PipelineRun run4 = service.getRun(fourth.getRunId());
        log.info("DECISION run4 BUILD cacheHit={} reason={}",
                buildStage(run4).getCacheHit(), buildStage(run4).getCacheReason());
        assertEquals(Boolean.FALSE, buildStage(run4).getCacheHit());
        assertTrue(buildStage(run4).getCacheReason().startsWith("CORRUPTED"),
                "tampered cache must be detected, evicted and rebuilt");

        // A subsequent identical run must be a HIT again (cache was repaired).
        TriggerResult fifth = service.trigger(pipelineId, request("idem-cache-5", "main", "src-A", "builder-2"));
        assertEquals(Boolean.TRUE, buildStage(service.getRun(fifth.getRunId())).getCacheHit());

        // Capacity is 3; force LRU eviction with further distinct inputs.
        service.trigger(pipelineId, request("idem-cache-6", "main", "src-B", "builder-9"));
        service.trigger(pipelineId, request("idem-cache-7", "main", "src-C", "builder-9"));
        int size = service.listCache(pipelineId).size();
        log.info("DECISION cache entries observed for pipeline={} size={} (global cap=3)",
                pipelineId, size);
        assertTrue(size <= 3, "cache must never exceed configured capacity");
        assertFalse(service.listCache(pipelineId).isEmpty());
    }
}
