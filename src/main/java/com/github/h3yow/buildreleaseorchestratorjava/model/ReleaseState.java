package com.github.h3yow.buildreleaseorchestratorjava.model;

import java.time.Instant;

public class ReleaseState {

    private final String pipelineId;
    private volatile String currentVersion;
    private volatile String currentChecksum;
    private volatile String previousVersion;
    private volatile String previousChecksum;
    private volatile String lastRunId;
    private volatile Instant updatedAt;

    public ReleaseState(String pipelineId) {
        this.pipelineId = pipelineId;
    }

    public String getPipelineId() {
        return pipelineId;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(String currentVersion) {
        this.currentVersion = currentVersion;
    }

    public String getCurrentChecksum() {
        return currentChecksum;
    }

    public void setCurrentChecksum(String currentChecksum) {
        this.currentChecksum = currentChecksum;
    }

    public String getPreviousVersion() {
        return previousVersion;
    }

    public void setPreviousVersion(String previousVersion) {
        this.previousVersion = previousVersion;
    }

    public String getPreviousChecksum() {
        return previousChecksum;
    }

    public void setPreviousChecksum(String previousChecksum) {
        this.previousChecksum = previousChecksum;
    }

    public String getLastRunId() {
        return lastRunId;
    }

    public void setLastRunId(String lastRunId) {
        this.lastRunId = lastRunId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
