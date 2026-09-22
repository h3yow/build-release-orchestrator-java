package com.github.h3yow.buildreleaseorchestratorjava.web;

public class TriggerRequest {

    private String idempotencyKey;
    private String requestedVersion;
    private String branch = "main";
    private String sources = "baseline";
    private String compatibilityTag = "builder-1";
    private long stageDelayMillis = 2L;
    private String failStage;
    private int failAttempt;

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestedVersion() {
        return requestedVersion;
    }

    public void setRequestedVersion(String requestedVersion) {
        this.requestedVersion = requestedVersion;
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

    public String getFailStage() {
        return failStage;
    }

    public void setFailStage(String failStage) {
        this.failStage = failStage;
    }

    public int getFailAttempt() {
        return failAttempt;
    }

    public void setFailAttempt(int failAttempt) {
        this.failAttempt = failAttempt;
    }
}
