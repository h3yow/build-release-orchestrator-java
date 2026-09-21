package com.github.h3yow.buildreleaseorchestratorjava.domain;

import java.time.Instant;

/**
 * 制品：版本唯一，记录来源与校验和，支持可追溯。
 */
public class Artifact {
    private final String pipelineId;
    private final String version;
    private final String checksum;
    private final String source;
    private final String content;
    private final Instant createdAt;

    public Artifact(String pipelineId, String version, String checksum, String source, String content, Instant createdAt) {
        this.pipelineId = pipelineId;
        this.version = version;
        this.checksum = checksum;
        this.source = source;
        this.content = content;
        this.createdAt = createdAt;
    }

    public String getPipelineId() { return pipelineId; }
    public String getVersion() { return version; }
    public String getChecksum() { return checksum; }
    public String getSource() { return source; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
