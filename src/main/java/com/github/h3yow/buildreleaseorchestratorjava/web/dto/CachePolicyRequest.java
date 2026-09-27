package com.github.h3yow.buildreleaseorchestratorjava.web.dto;

/**
 * 缓存淘汰策略切换请求：policy 仅接受 FIFO/LRU，无法识别时返回 400 + UNKNOWN_EVICTION_POLICY。
 */
public class CachePolicyRequest {
    private String policy;

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }
}
