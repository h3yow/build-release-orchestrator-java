package com.github.h3yow.buildreleaseorchestratorjava.tests;

import com.github.h3yow.buildreleaseorchestratorjava.engine.OrchestratorService;
import com.github.h3yow.buildreleaseorchestratorjava.error.OrchestratorException;
import com.github.h3yow.buildreleaseorchestratorjava.model.Artifact;
import com.github.h3yow.buildreleaseorchestratorjava.model.TriggerOutcome;
import com.github.h3yow.buildreleaseorchestratorjava.model.TriggerResult;
import com.github.h3yow.buildreleaseorchestratorjava.web.TriggerRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class ConcurrencyIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyIntegrationTest.class);

    @Autowired
    private OrchestratorService service;

    @Test
    void duplicateIdempotencyKeySubmittedConcurrentlyProducesSingleRun() throws Exception {
        String pipelineId = "concurrency-idem-pipeline";
        service.registerPipeline(pipelineId, "c1",
                List.of("CHECKOUT", "BUILD", "TEST", "PACKAGE", "RELEASE"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<TriggerResult> results = new ArrayList<>(new java.util.Vector<>());
        String idempotencyKey = "idem-concurrent-1";

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    TriggerRequest request = new TriggerRequest();
                    request.setIdempotencyKey(idempotencyKey);
                    request.setStageDelayMillis(1L);
                    results.add(service.trigger(pipelineId, request));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        long started = results.stream()
                .filter(r -> r.getOutcome() == TriggerOutcome.STARTED).count();
        long replays = results.stream()
                .filter(r -> r.getOutcome() == TriggerOutcome.DUPLICATE_REPLAY).count();
        log.info("DECISION concurrent same-key threads={} STARTED={} DUPLICATE_REPLAY={}",
                threads, started, replays);
        assertEquals(1, started, "exactly one trigger must start a run");
        assertEquals(threads - 1, replays, "all other submissions replay the same run");

        String uniqueRunId = results.get(0).getRunId();
        assertTrue(results.stream().allMatch(r -> r.getRunId().equals(uniqueRunId)),
                "every response must reference the same run");
        assertEquals(1, service.listRuns(pipelineId).size());
        assertEquals(1, service.listArtifacts(pipelineId).size());
        assertEquals("1.0.1", service.listArtifacts(pipelineId).get(0).getVersion());
    }

    @Test
    void distinctConcurrentTriggersProduceMonotonicGaplessVersions() throws Exception {
        String pipelineId = "concurrency-versions-pipeline";
        service.registerPipeline(pipelineId, "c2",
                List.of("CHECKOUT", "BUILD", "TEST", "PACKAGE", "RELEASE"));

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Map<Integer, String> versionByThread = new ConcurrentHashMap<>();

        for (int i = 0; i < threads; i++) {
            int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    TriggerRequest request = new TriggerRequest();
                    request.setIdempotencyKey("idem-distinct-" + index);
                    request.setSources("source-" + index % 3);
                    request.setStageDelayMillis(1L);
                    TriggerResult result = service.trigger(pipelineId, request);
                    versionByThread.put(index, result.getVersion());
                    log.info("DECISION concurrent trigger index={} runId={} version={} outcome={}",
                            index, result.getRunId(), result.getVersion(), result.getOutcome());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();

        List<Artifact> artifacts = service.listArtifacts(pipelineId);
        List<String> versions = artifacts.stream().map(Artifact::getVersion)
                .sorted().collect(Collectors.toList());
        log.info("VERIFY committed versions={}", versions);
        assertEquals(threads, artifacts.size(), "every distinct trigger commits one artifact");
        for (int patch = 1; patch <= threads; patch++) {
            assertTrue(versions.contains("1.0." + patch),
                    "version 1.0." + patch + " must exist (no gaps, no jumps)");
        }
        assertEquals(threads, versionByThread.size());
    }

    @Test
    void explicitDuplicateVersionConflictIsRejected() {
        String pipelineId = "version-conflict-pipeline";
        service.registerPipeline(pipelineId, "c3",
                List.of("CHECKOUT", "BUILD", "TEST", "PACKAGE", "RELEASE"));

        TriggerRequest first = new TriggerRequest();
        first.setIdempotencyKey("idem-explicit-1");
        first.setRequestedVersion("1.0.1");
        first.setStageDelayMillis(1L);
        assertEquals("1.0.1", service.trigger(pipelineId, first).getVersion());

        TriggerRequest second = new TriggerRequest();
        second.setIdempotencyKey("idem-explicit-2");
        second.setRequestedVersion("1.0.1");
        second.setStageDelayMillis(1L);
        OrchestratorException ex = assertThrows(OrchestratorException.class,
                () -> service.trigger(pipelineId, second));
        log.info("DECISION duplicate explicit version rejected errorCode={} message={}",
                ((OrchestratorException) ex).getErrorCode(), ex.getMessage());
        // Next monotonic version is already 1.0.2, so re-requesting 1.0.1 is rejected
        // at allocation time as a non-monotonic VERSION_CONFLICT.
        assertEquals(com.github.h3yow.buildreleaseorchestratorjava.error.ErrorCode.VERSION_CONFLICT,
                ((OrchestratorException) ex).getErrorCode());
        assertEquals(1, service.listArtifacts(pipelineId).size(),
                "rejected trigger leaves no partial artifact");
        assertEquals(1, service.listRuns(pipelineId).size(),
                "rejected trigger creates no run");
    }
}
