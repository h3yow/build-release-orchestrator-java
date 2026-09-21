package com.github.h3yow.buildreleaseorchestratorjava.web.dto;

/**
 * 触发请求：幂等键必填，failAtStage 为本地故障注入点（模拟流水线失败）。
 */
public class TriggerRequest {
    private String idempotencyKey;
    private String failAtStage;

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getFailAtStage() { return failAtStage; }
    public void setFailAtStage(String failAtStage) { this.failAtStage = failAtStage; }
}
