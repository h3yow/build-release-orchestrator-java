package com.github.h3yow.buildreleaseorchestratorjava.web.dto;

/**
 * 触发请求：幂等键必填，failAtStage 为本地故障注入点（模拟流水线失败）。
 * sourceRef/sourceContent 为本次构建输入（源码标识与内容），共同决定缓存复用与制品内容。
 * requestedVersion 为期望产出的版本，已被占用时按冲突拒绝。
 */
public class TriggerRequest {
    private String idempotencyKey;
    private String failAtStage;
    private String sourceRef;
    private String sourceContent;
    private String requestedVersion;

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getFailAtStage() { return failAtStage; }
    public void setFailAtStage(String failAtStage) { this.failAtStage = failAtStage; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }
    public String getSourceContent() { return sourceContent; }
    public void setSourceContent(String sourceContent) { this.sourceContent = sourceContent; }
    public String getRequestedVersion() { return requestedVersion; }
    public void setRequestedVersion(String requestedVersion) { this.requestedVersion = requestedVersion; }
}
