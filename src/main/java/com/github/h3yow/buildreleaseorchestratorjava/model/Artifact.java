package com.github.h3yow.buildreleaseorchestratorjava.model;

import java.time.Instant;

public class Artifact {

    private final String pipelineId;
    private final String version;
    private final String checksum;
    private final String sourceRunId;
    private final String buildInputHash;
    private final String content;
    private final Instant createdAt;

    public Artifact(String pipelineId, String version, String checksum, String sourceRunId,
                    String buildInputHash, String content, Instant createdAt) {
        this.pipelineId = pipelineId;
        this.version = version;
        this.checksum = checksum;
        this.sourceRunId = sourceRunId;
        this.buildInputHash = buildInputHash;
        this.content = content;
        this.createdAt = createdAt;
    }

    public String getPipelineId() {
        return pipelineId;
    }

    public String getVersion() {
        return version;
    }

    public String getChecksum() {
        return checksum;
    }

    public String getSourceRunId() {
        return sourceRunId;
    }

    public String getBuildInputHash() {
        return buildInputHash;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
