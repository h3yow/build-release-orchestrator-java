package com.github.h3yow.buildreleaseorchestratorjava.web.dto;

/**
 * 回滚请求：目标版本必填。
 */
public class RollbackRequest {
    private String targetVersion;

    public String getTargetVersion() { return targetVersion; }
    public void setTargetVersion(String targetVersion) { this.targetVersion = targetVersion; }
}
