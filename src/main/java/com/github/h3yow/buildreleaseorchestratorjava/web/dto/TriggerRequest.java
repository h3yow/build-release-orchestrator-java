package com.github.h3yow.buildreleaseorchestratorjava.web.dto;

/**
 * 触发请求：
 * <ul>
 *   <li>idempotencyKey 必填；</li>
 *   <li>sourceRef/sourceContent 为本次构建实际输入（源码标识与内容），内容决定构建指纹，
 *       未提供时使用按流水线派生的默认输入以保持兼容；</li>
 *   <li>expectedVersion 指定本次期望产出的版本（格式 1.0.N），冲突时拒绝；</li>
 *   <li>failAtStage 为本地故障注入点。</li>
 * </ul>
 */
public class TriggerRequest {
    private String idempotencyKey;
    private String failAtStage;
    private String sourceRef;
    private String sourceContent;
    private String expectedVersion;

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getFailAtStage() { return failAtStage; }
    public void setFailAtStage(String failAtStage) { this.failAtStage = failAtStage; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }
    public String getSourceContent() { return sourceContent; }
    public void setSourceContent(String sourceContent) { this.sourceContent = sourceContent; }
    public String getExpectedVersion() { return expectedVersion; }
    public void setExpectedVersion(String expectedVersion) { this.expectedVersion = expectedVersion; }
}
