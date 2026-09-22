package com.github.h3yow.buildreleaseorchestratorjava.engine;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.error.ErrorCode;
import com.github.h3yow.buildreleaseorchestratorjava.error.OrchestratorException;
import com.github.h3yow.buildreleaseorchestratorjava.model.Artifact;
import com.github.h3yow.buildreleaseorchestratorjava.model.CacheEntry;
import com.github.h3yow.buildreleaseorchestratorjava.model.ErrorView;
import com.github.h3yow.buildreleaseorchestratorjava.model.PipelineDefinition;
import com.github.h3yow.buildreleaseorchestratorjava.model.PipelineRun;
import com.github.h3yow.buildreleaseorchestratorjava.model.ReleaseState;
import com.github.h3yow.buildreleaseorchestratorjava.model.RunStatus;
import com.github.h3yow.buildreleaseorchestratorjava.model.StageName;
import com.github.h3yow.buildreleaseorchestratorjava.model.TriggerOutcome;
import com.github.h3yow.buildreleaseorchestratorjava.model.TriggerResult;
import com.github.h3yow.buildreleaseorchestratorjava.web.TriggerRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single entry point for triggering, retrying, rolling back and querying.
 *
 * <p>Concurrency model: one fair reentrant lock per pipeline. Trigger, retry
 * and rollback for the same pipeline serialize on that lock, which makes
 * version allocation monotonic and prevents duplicate runs / duplicate
 * artifacts / half-updated pointers. The global idempotency index is checked
 * inside the same critical section, so a duplicate submission receives a
 * distinguishable {@code DUPLICATE_REPLAY} result without creating anything.
 */
@Service
public class OrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);

    private final PipelineRegistry registry;
    private final BuildEngine buildEngine;
    private final ArtifactStore artifactStore;
    private final CacheService cacheService;
    private final ReleaseService releaseService;
    private final OrchestratorProperties properties;

    private final Map<String, PipelineRun> runs = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> pipelineLocks = new ConcurrentHashMap<>();

    public OrchestratorService(PipelineRegistry registry, BuildEngine buildEngine,
                               ArtifactStore artifactStore, CacheService cacheService,
                               ReleaseService releaseService,
                               OrchestratorProperties properties) {
        this.registry = registry;
        this.buildEngine = buildEngine;
        this.artifactStore = artifactStore;
        this.cacheService = cacheService;
        this.releaseService = releaseService;
        this.properties = properties;
    }

    public PipelineDefinition registerPipeline(String pipelineId, String name, List<String> stages) {
        return registry.define(pipelineId, name, stages);
    }

    public List<PipelineDefinition> registryList() {
        return registry.list();
    }

    public TriggerResult trigger(String pipelineId, TriggerRequest request) {
        PipelineDefinition definition = registry.require(pipelineId);
        String idempotencyKey = request.getIdempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new OrchestratorException(ErrorCode.ILLEGAL_STATE,
                    "idempotencyKey is required");
        }
        // The build input hash intentionally excludes the builder compatibility tag:
        // the tag is an independent cache dimension so that a tag change is observable
        // as INCOMPATIBLE (rebuild) rather than a plain MISS.
        String buildInputHash = Checksums.sha256(
                request.getBranch() + "|" + request.getSources());
        ReentrantLock lock = lockOf(pipelineId);
        lock.lock();
        try {
            String existingRunId = idempotencyIndex.get(idempotencyKey);
            if (existingRunId != null) {
                PipelineRun existing = runs.get(existingRunId);
                if (existing != null && existing.getPipelineId().equals(pipelineId)) {
                    log.info("trigger duplicate idempotencyKey={} pipeline={} replayedRunId={} status={}",
                            idempotencyKey, pipelineId, existingRunId, existing.getStatus());
                    return TriggerResult.replay(existing);
                }
                log.warn("trigger conflict idempotencyKey={} alreadyBoundTo={} requestedPipeline={}",
                        idempotencyKey, existingRunId, pipelineId);
                return rejected(pipelineId, idempotencyKey, TriggerOutcome.REJECTED_CONFLICT,
                        new ErrorView(ErrorCode.IDEMPOTENT_KEY_CONFLICT,
                                "idempotency key already bound to another run: " + existingRunId));
            }

            long runCount = runs.values().stream()
                    .filter(r -> r.getPipelineId().equals(pipelineId)).count();
            if (runCount >= properties.getMaxRunsPerPipeline()) {
                log.warn("trigger rejected pipeline={} reason=run-limit count={} limit={}",
                        pipelineId, runCount, properties.getMaxRunsPerPipeline());
                return rejected(pipelineId, idempotencyKey, TriggerOutcome.REJECTED_LIMIT,
                        new ErrorView(ErrorCode.RUN_LIMIT_EXCEEDED,
                                "run limit " + properties.getMaxRunsPerPipeline()
                                        + " reached for pipeline " + pipelineId));
            }

            String runId = "run-" + UUID.randomUUID();
            PipelineRun run = newRun(runId, pipelineId, idempotencyKey.trim(),
                    buildInputHash, normalizeRequestedVersion(request.getRequestedVersion()));
            idempotencyIndex.put(idempotencyKey.trim(), runId);
            runs.put(runId, run);
            for (StageName stageName : definition.stages()) {
                run.getStages().put(stageName, new com.github.h3yow.buildreleaseorchestratorjava
                        .model.StageExecution(stageName));
            }
            log.info("trigger accepted idempotencyKey={} pipeline={} runId={} requestedVersion={} buildInputHash={}",
                    idempotencyKey, pipelineId, runId,
                    request.getRequestedVersion() == null ? "<auto>" : request.getRequestedVersion(),
                    buildInputHash);

            BuildEngine.BuildRequest buildRequest = toBuildRequest(request);
            run.setBranch(request.getBranch());
            run.setSources(request.getSources());
            run.setCompatibilityTag(request.getCompatibilityTag());
            run.setStageDelayMillis(request.getStageDelayMillis());
            run.setStatus(RunStatus.RUNNING);
            run.setStartedAt(Instant.now());
            run.incrementAttempt();
            try {
                buildEngine.execute(run, definition, buildRequest);
            } catch (OrchestratorException e) {
                // A rejection discovered while executing (e.g. version conflict at PACKAGE)
                // must leave no run and no idempotency reservation behind.
                runs.remove(runId);
                idempotencyIndex.remove(idempotencyKey.trim());
                log.warn("trigger rolled back idempotencyKey={} runId={} errorCode={} reason=rejected-no-partial-state",
                        idempotencyKey, runId, e.getErrorCode());
                throw e;
            }
            return toResult(run, TriggerOutcome.STARTED, null);
        } finally {
            lock.unlock();
        }
    }

    public TriggerResult retry(String runId) {
        PipelineRun run = getRun(runId);
        ReentrantLock lock = lockOf(run.getPipelineId());
        lock.lock();
        try {
            if (run.getStatus() != RunStatus.FAILED) {
                throw new OrchestratorException(ErrorCode.RUN_NOT_RETRYABLE,
                        "run " + runId + " is " + run.getStatus() + ", only FAILED runs can retry");
            }
            PipelineDefinition definition = registry.require(run.getPipelineId());
            run.incrementAttempt();
            run.setStatus(RunStatus.RUNNING);
            run.setStartedAt(Instant.now());
            run.setFailureReason("");
            log.info("retry accepted runId={} pipeline={} attempt={} resumeFrom=failed-stage",
                    runId, run.getPipelineId(), run.getAttempt());
            // Replay with the exact build inputs of the original attempt; failure
            // injection is cleared so a retry can succeed.
            BuildEngine.BuildRequest retryRequest = new BuildEngine.BuildRequest(
                    run.getBranch(), run.getSources(), run.getCompatibilityTag(),
                    run.getStageDelayMillis(), null, 0);
            buildEngine.execute(run, definition, retryRequest);
            return toResult(run, TriggerOutcome.STARTED, null);
        } finally {
            lock.unlock();
        }
    }

    public PipelineRun getRun(String runId) {
        PipelineRun run = runs.get(runId);
        if (run == null) {
            throw new OrchestratorException(ErrorCode.RUN_NOT_FOUND, "run not found: " + runId);
        }
        return run;
    }

    public List<PipelineRun> listRuns(String pipelineId) {
        registry.require(pipelineId);
        List<PipelineRun> result = new ArrayList<>();
        for (PipelineRun run : runs.values()) {
            if (run.getPipelineId().equals(pipelineId)) {
                result.add(run);
            }
        }
        result.sort((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()));
        return result;
    }

    public List<Artifact> listArtifacts(String pipelineId) {
        registry.require(pipelineId);
        return artifactStore.list(pipelineId);
    }

    public ReleaseState getRelease(String pipelineId) {
        registry.require(pipelineId);
        return releaseService.get(pipelineId);
    }

    public ReleaseState rollback(String pipelineId, String targetVersion) {
        registry.require(pipelineId);
        ReentrantLock lock = lockOf(pipelineId);
        lock.lock();
        try {
            return releaseService.rollback(pipelineId, targetVersion);
        } finally {
            lock.unlock();
        }
    }

    public List<CacheEntry> listCache(String pipelineId) {
        registry.require(pipelineId);
        return cacheService.list(pipelineId);
    }

    public boolean corruptCache(String pipelineId, String buildInputHash) {
        registry.require(pipelineId);
        return cacheService.debugCorrupt(pipelineId, buildInputHash);
    }

    public boolean tamperArtifact(String pipelineId, String version) {
        registry.require(pipelineId);
        return artifactStore.debugTamper(pipelineId, version);
    }

    private PipelineRun newRun(String runId, String pipelineId, String idempotencyKey,
                               String buildInputHash, String requestedVersion) {
        return new PipelineRun(runId, pipelineId, idempotencyKey,
                buildInputHash, requestedVersion, Instant.now());
    }

    private static String normalizeRequestedVersion(String requestedVersion) {
        return requestedVersion == null || requestedVersion.isBlank() ? null
                : requestedVersion.trim();
    }

    private static BuildEngine.BuildRequest toBuildRequest(TriggerRequest request) {
        return new BuildEngine.BuildRequest(
                request.getBranch(),
                request.getSources(),
                request.getCompatibilityTag(),
                request.getStageDelayMillis(),
                request.getFailStage(),
                request.getFailAttempt());
    }

    private TriggerResult rejected(String runId, String idempotencyKey, TriggerOutcome outcome,
                                   ErrorView error) {
        return new TriggerResult(outcome, runId, null, idempotencyKey, null,
                RunStatus.PENDING, outcome.name(), error);
    }

    private TriggerResult toResult(PipelineRun run, TriggerOutcome outcome, String message) {
        TriggerOutcome effectiveOutcome = run.getStatus() == RunStatus.FAILED
                ? TriggerOutcome.FAILED : outcome;
        ErrorView error = run.getStatus() == RunStatus.FAILED
                ? new ErrorView(ErrorCode.ILLEGAL_STATE, run.getFailureReason()) : null;
        return new TriggerResult(effectiveOutcome, run.getRunId(), run.getPipelineId(),
                run.getIdempotencyKey(), run.getResolvedVersion(), run.getStatus(),
                message == null ? effectiveOutcome.name() : message, error);
    }

    private ReentrantLock lockOf(String pipelineId) {
        return pipelineLocks.computeIfAbsent(pipelineId,
                k -> new ReentrantLock(true));
    }
}
