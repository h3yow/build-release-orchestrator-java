package com.github.h3yow.buildreleaseorchestratorjava.web;

public class RollbackRequest {

    private String targetVersion;

    public String getTargetVersion() {
        return targetVersion;
    }

    public void setTargetVersion(String targetVersion) {
        this.targetVersion = targetVersion;
    }
}
