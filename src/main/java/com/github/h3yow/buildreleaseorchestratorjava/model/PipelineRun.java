package com.github.h3yow.buildreleaseorchestratorjava.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PipelineRun {

    private final String runId;
    private final String pipelineId;
    private final String idempotencyKey;
    private final String buildInputHash;
    private final String requestedVersion;
    private final Instant createdAt;
    private volatile RunStatus status = RunStatus.PENDING;
    private volatile String resolvedVersion;
    private volatile String artifactChecksum;
    private volatile String failureReason = "";
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile int attempt = 0;
    private volatile String branch = "main";
    private volatile String sources = "baseline";
    private volatile String compatibilityTag = "builder-1";
    private volatile long stageDelayMillis = 2L;
    private final Map<StageName, StageExecution> stages = new LinkedHashMap<>();

    public PipelineRun(String runId, String pipelineId, String idempotencyKey,
                       String buildInputHash, String requestedVersion, Instant createdAt) {
        this.runId = runId;
        this.pipelineId = pipelineId;
        this.idempotencyKey = idempotencyKey;
        this.buildInputHash = buildInputHash;
        this.requestedVersion = requestedVersion;
        this.createdAt = createdAt;
    }

    public String getRunId() {
        return runId;
    }

    public String getPipelineId() {
        return pipelineId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getBuildInputHash() {
        return buildInputHash;
    }

    public String getRequestedVersion() {
        return requestedVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public String getResolvedVersion() {
        return resolvedVersion;
    }

    public void setResolvedVersion(String resolvedVersion) {
        this.resolvedVersion = resolvedVersion;
    }

    public String getArtifactChecksum() {
        return artifactChecksum;
    }

    public void setArtifactChecksum(String artifactChecksum) {
        this.artifactChecksum = artifactChecksum;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public void incrementAttempt() {
        this.attempt++;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getSources() {
        return sources;
    }

    public void setSources(String sources) {
        this.sources = sources;
    }

    public String getCompatibilityTag() {
        return compatibilityTag;
    }

    public void setCompatibilityTag(String compatibilityTag) {
        this.compatibilityTag = compatibilityTag;
    }

    public long getStageDelayMillis() {
        return stageDelayMillis;
    }

    public void setStageDelayMillis(long stageDelayMillis) {
        this.stageDelayMillis = stageDelayMillis;
    }

    public Map<StageName, StageExecution> getStages() {
        return stages;
    }

    public List<StageExecution> stageList() {
        return new ArrayList<>(stages.values());
    }
}
