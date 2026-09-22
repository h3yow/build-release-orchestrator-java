package com.github.h3yow.buildreleaseorchestratorjava.model;

public class StageExecution {

    private final StageName stage;
    private volatile StageStatus status = StageStatus.PENDING;
    private volatile long startedAtMillis;
    private volatile long finishedAtMillis;
    private volatile long durationMillis;
    private volatile String message = "";
    private volatile Boolean cacheHit;
    private volatile String cacheReason = "";

    public StageExecution(StageName stage) {
        this.stage = stage;
    }

    public StageName getStage() {
        return stage;
    }

    public StageStatus getStatus() {
        return status;
    }

    public void setStatus(StageStatus status) {
        this.status = status;
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public void setStartedAtMillis(long startedAtMillis) {
        this.startedAtMillis = startedAtMillis;
    }

    public long getFinishedAtMillis() {
        return finishedAtMillis;
    }

    public void setFinishedAtMillis(long finishedAtMillis) {
        this.finishedAtMillis = finishedAtMillis;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = durationMillis;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Boolean getCacheHit() {
        return cacheHit;
    }

    public void setCacheHit(Boolean cacheHit) {
        this.cacheHit = cacheHit;
    }

    public String getCacheReason() {
        return cacheReason;
    }

    public void setCacheReason(String cacheReason) {
        this.cacheReason = cacheReason;
    }
}
