package com.github.h3yow.buildreleaseorchestratorjava.domain;

/**
 * 一次触发携带的构建输入：源码标识与源码内容。内容决定构建指纹，标识决定缓存命名空间。
 */
public class BuildInput {

    private final String sourceRef;
    private final String sourceContent;

    public BuildInput(String sourceRef, String sourceContent) {
        this.sourceRef = sourceRef;
        this.sourceContent = sourceContent;
    }

    public String getSourceRef() { return sourceRef; }
    public String getSourceContent() { return sourceContent; }
}
