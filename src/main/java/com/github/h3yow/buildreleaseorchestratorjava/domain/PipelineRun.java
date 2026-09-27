package com.github.h3yow.buildreleaseorchestratorjava.domain;

import java.time.Instant;
import java.util.List;

/**
 * 一次流水线运行：记录幂等键、阶段列表、产出版本与失败原因。
 */
public class PipelineRun {
    private final String runId;
    private final String pipelineId;
    private final String idempotencyKey;
    private volatile RunStatus status;
    private final List<StageResult> stages;
    private final Instant createdAt;
    private volatile String producedVersion;
    private volatile String failureReason;
    /** 构建缓存命中情况（RESOLVE_CACHE 阶段的判定结果），供查询 */
    private volatile Boolean cacheHit;
    private volatile String cacheReason;
    /** 本次触发携带的构建身份：源码标识与内容指纹 */
    private volatile String sourceRef;
    private volatile String sourceFingerprint;
    /** 触发时指定的期望版本（未指定为 null） */
    private volatile String expectedVersion;
    /** 是否复用了既有构建结果（true=未重新构建，内容与首次构建一致） */
    private volatile Boolean reusedBuild;

    public PipelineRun(String runId, String pipelineId, String idempotencyKey, List<StageResult> stages) {
        this.runId = runId;
        this.pipelineId = pipelineId;
        this.idempotencyKey = idempotencyKey;
        this.stages = stages;
        this.status = RunStatus.RUNNING;
        this.createdAt = Instant.now();
    }

    public String getRunId() { return runId; }
    public String getPipelineId() { return pipelineId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public RunStatus getStatus() { return status; }
    public void setStatus(RunStatus status) { this.status = status; }
    public List<StageResult> getStages() { return stages; }
    public Instant getCreatedAt() { return createdAt; }
    public String getProducedVersion() { return producedVersion; }
    public void setProducedVersion(String producedVersion) { this.producedVersion = producedVersion; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public Boolean getCacheHit() { return cacheHit; }
    public void setCacheHit(Boolean cacheHit) { this.cacheHit = cacheHit; }
    public String getCacheReason() { return cacheReason; }
    public void setCacheReason(String cacheReason) { this.cacheReason = cacheReason; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }
    public String getSourceFingerprint() { return sourceFingerprint; }
    public void setSourceFingerprint(String sourceFingerprint) { this.sourceFingerprint = sourceFingerprint; }
    public String getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(String expectedVersion) { this.expectedVersion = expectedVersion; }
    public Boolean getReusedBuild() { return reusedBuild; }
    public void setReusedBuild(Boolean reusedBuild) { this.reusedBuild = reusedBuild; }
}
