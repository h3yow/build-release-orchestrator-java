package com.github.h3yow.buildreleaseorchestratorjava.engine;

import com.github.h3yow.buildreleaseorchestratorjava.error.ErrorCode;
import com.github.h3yow.buildreleaseorchestratorjava.error.OrchestratorException;
import com.github.h3yow.buildreleaseorchestratorjava.model.Artifact;
import com.github.h3yow.buildreleaseorchestratorjava.model.PipelineDefinition;
import com.github.h3yow.buildreleaseorchestratorjava.model.PipelineRun;
import com.github.h3yow.buildreleaseorchestratorjava.model.RunStatus;
import com.github.h3yow.buildreleaseorchestratorjava.model.StageExecution;
import com.github.h3yow.buildreleaseorchestratorjava.model.StageName;
import com.github.h3yow.buildreleaseorchestratorjava.model.StageStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Executes pipeline stages locally and deterministically.
 *
 * <p>Semantics:
 * <ul>
 *   <li>stages run in definition order, the first failed stage stops the run
 *       and later stages stay PENDING;</li>
 *   <li>a retry re-enters this method: SUCCEEDED stages are not re-executed and
 *       their recorded status and duration are preserved (never re-timed);</li>
 *   <li>the BUILD stage consults the cache; a verified HIT is reused, while
 *       MISS / CORRUPTED / INCOMPATIBLE trigger a fresh build with an
 *       explainable reason recorded on the stage;</li>
 *   <li>PACKAGE registers the artifact (which also commits the monotonic
 *       version), RELEASE verifies and moves the release pointer. If either
 *       fails, no pointer is half-updated and the run stops.</li>
 * </ul>
 */
@Service
public class BuildEngine {

    private static final Logger log = LoggerFactory.getLogger(BuildEngine.class);

    public record BuildRequest(
            String branch,
            String sources,
            String compatibilityTag,
            long stageDelayMillis,
            String failStage,
            int failAttempt
    ) {
        public long effectiveStageDelayMillis() {
            return stageDelayMillis <= 0 ? 2L : stageDelayMillis;
        }
    }

    private final ArtifactStore artifactStore;
    private final CacheService cacheService;
    private final ReleaseService releaseService;
    private final VersionManager versionManager;

    public BuildEngine(ArtifactStore artifactStore, CacheService cacheService,
                       ReleaseService releaseService, VersionManager versionManager) {
        this.artifactStore = artifactStore;
        this.cacheService = cacheService;
        this.releaseService = releaseService;
        this.versionManager = versionManager;
    }

    public void execute(PipelineRun run, PipelineDefinition definition, BuildRequest request) {
        log.info("run execution start runId={} pipeline={} attempt={} idempotencyKey={} buildInputHash={}",
                run.getRunId(), run.getPipelineId(), run.getAttempt(),
                run.getIdempotencyKey(), run.getBuildInputHash());
        try {
            for (StageName stageName : definition.stages()) {
                StageExecution stage = run.getStages()
                        .computeIfAbsent(stageName, StageExecution::new);
                if (stage.getStatus() == StageStatus.SUCCEEDED) {
                    log.info("stage not re-executed runId={} stage={} reason=already-succeeded durationMs={}",
                            run.getRunId(), stageName, stage.getDurationMillis());
                    continue;
                }
                runStage(run, stage, request);
            }
            Artifact artifact = artifactStore.verify(run.getPipelineId(), run.getResolvedVersion());
            run.setArtifactChecksum(artifact.getChecksum());
        } catch (OrchestratorException e) {
            if (isRejection(e.getErrorCode())) {
                // Rejections (version/checksum conflicts) propagate so the orchestrator can
                // roll back run registration; they are not pipeline execution failures.
                log.warn("run rejected before completion runId={} errorCode={} message={}",
                        run.getRunId(), e.getErrorCode(), e.getMessage());
                throw e;
            }
            failRun(run, e.getErrorCode().name() + ": " + e.getMessage());
            return;
        } catch (RuntimeException e) {
            failRun(run, "UNEXPECTED: " + e.getMessage());
            return;
        }
        run.setStatus(RunStatus.SUCCEEDED);
        run.setFinishedAt(Instant.now());
        log.info("run execution succeeded runId={} pipeline={} version={} checksum={}",
                run.getRunId(), run.getPipelineId(), run.getResolvedVersion(),
                run.getArtifactChecksum());
    }

    private void runStage(PipelineRun run, StageExecution stage, BuildRequest request) {
        stage.setStatus(StageStatus.RUNNING);
        stage.setStartedAtMillis(System.currentTimeMillis());
        long start = System.nanoTime();
        try {
            switch (stage.getStage()) {
                case CHECKOUT -> doCheckout(run, stage, request);
                case BUILD -> doBuild(run, stage, request);
                case TEST -> doTest(run, stage, request);
                case PACKAGE -> doPackage(run, stage, request);
                case RELEASE -> doRelease(run, stage, request);
            }
            sleep(request.effectiveStageDelayMillis());
            stage.setStatus(StageStatus.SUCCEEDED);
            stage.setDurationMillis((System.nanoTime() - start) / 1_000_000);
            stage.setFinishedAtMillis(System.currentTimeMillis());
            log.info("stage succeeded runId={} stage={} attempt={} durationMs={}",
                    run.getRunId(), stage.getStage(), run.getAttempt(),
                    stage.getDurationMillis());
        } catch (RuntimeException e) {
            stage.setDurationMillis((System.nanoTime() - start) / 1_000_000);
            stage.setFinishedAtMillis(System.currentTimeMillis());
            stage.setStatus(StageStatus.FAILED);
            stage.setMessage(e.getMessage());
            log.warn("stage failed runId={} stage={} attempt={} durationMs={} error={}",
                    run.getRunId(), stage.getStage(), run.getAttempt(),
                    stage.getDurationMillis(), e.getMessage());
            throw e;
        }
    }

    private void doCheckout(PipelineRun run, StageExecution stage, BuildRequest request) {
        if (shouldFail(stage, request, run)) {
            throw new OrchestratorException(ErrorCode.ILLEGAL_STATE,
                    "simulated checkout failure");
        }
        stage.setMessage("checked out " + request.branch() + " (" + request.sources() + ")");
    }

    private void doBuild(PipelineRun run, StageExecution stage, BuildRequest request) {
        String payload = "build-output:" + run.getBuildInputHash();
        String checksum = Checksums.sha256(payload);
        CacheService.LookupResult lookup = cacheService.lookup(
                run.getPipelineId(), run.getBuildInputHash(), request.compatibilityTag());
        stage.setCacheHit(lookup.hit());
        stage.setCacheReason(lookup.reason());
        if (lookup.hit()) {
            stage.setMessage("reused cached build (" + lookup.reason() + ")");
            return;
        }
        if (shouldFail(stage, request, run)) {
            throw new OrchestratorException(ErrorCode.ILLEGAL_STATE, "simulated build failure");
        }
        cacheService.put(run.getPipelineId(), run.getBuildInputHash(),
                request.compatibilityTag(), payload, checksum);
        stage.setMessage("fresh build (" + lookup.reason() + ")");
    }

    private void doTest(PipelineRun run, StageExecution stage, BuildRequest request) {
        if (shouldFail(stage, request, run)) {
            throw new OrchestratorException(ErrorCode.ILLEGAL_STATE, "simulated test failure");
        }
        stage.setMessage("all local tests passed for " + run.getBuildInputHash());
    }

    private void doPackage(PipelineRun run, StageExecution stage, BuildRequest request) {
        // Version is allocated at the moment an artifact is actually produced,
        // so runs failing before PACKAGE never consume/skip a version number.
        if (run.getResolvedVersion() == null) {
            run.setResolvedVersion(versionManager.allocate(
                    run.getPipelineId(), run.getRequestedVersion()));
        }
        String content = "artifact|" + run.getPipelineId() + "|" + run.getResolvedVersion()
                + "|" + run.getBuildInputHash();
        String checksum = Checksums.sha256(content);
        Artifact artifact = artifactStore.register(run.getPipelineId(), run.getResolvedVersion(),
                checksum, run.getRunId(), run.getBuildInputHash(), content);
        run.setArtifactChecksum(artifact.getChecksum());
        stage.setMessage("packaged " + artifact.getVersion() + " checksum=" + checksum);
    }

    private void doRelease(PipelineRun run, StageExecution stage, BuildRequest request) {
        Artifact artifact = artifactStore.require(run.getPipelineId(), run.getResolvedVersion());
        releaseService.publish(run.getPipelineId(), artifact, run.getRunId());
        // Now that the pointer has moved, converge retention while protecting
        // the current and previous release artifacts.
        artifactStore.enforceRetention(run.getPipelineId());
        stage.setMessage("released " + artifact.getVersion()
                + " checksum=" + artifact.getChecksum());
    }

    /**
     * Deterministic failure injection used by tests: the stage fails only when
     * the request names it and the current run attempt matches. A
     * {@code failAttempt <= 0} means "fail on every attempt".
     */
    private boolean shouldFail(StageExecution stage, BuildRequest request, PipelineRun run) {
        if (request.failStage() == null) {
            return false;
        }
        boolean stageMatches = stage.getStage().name()
                .equalsIgnoreCase(request.failStage().trim());
        boolean attemptMatches = request.failAttempt() <= 0
                || request.failAttempt() == run.getAttempt();
        return stageMatches && attemptMatches;
    }

    private static boolean isRejection(ErrorCode code) {
        return code == ErrorCode.VERSION_ALREADY_EXISTS
                || code == ErrorCode.ARTIFACT_CHECKSUM_MISMATCH
                || code == ErrorCode.VERSION_CONFLICT
                || code == ErrorCode.ARTIFACT_LIMIT_EXCEEDED
                || code == ErrorCode.CACHE_LIMIT_EXCEEDED;
    }

    private void failRun(PipelineRun run, String reason) {
        run.setStatus(RunStatus.FAILED);
        run.setFailureReason(reason);
        run.setFinishedAt(Instant.now());
        log.warn("run failed runId={} pipeline={} reason={}",
                run.getRunId(), run.getPipelineId(), reason);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
