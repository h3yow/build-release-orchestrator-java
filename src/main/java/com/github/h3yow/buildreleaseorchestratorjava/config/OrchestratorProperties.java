package com.github.h3yow.buildreleaseorchestratorjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 编排器可配置上限：运行数量、制品保留量、缓存容量与淘汰策略。
 */
@Component
@ConfigurationProperties(prefix = "orchestrator")
public class OrchestratorProperties {
    /** 同时保留的最大运行记录数，超限按策略淘汰最早已完成运行 */
    private int maxRuns = 100;
    /** 每条流水线保留的最大制品数，超限淘汰最旧非当前发布制品 */
    private int maxArtifactsPerPipeline = 10;
    /** 构建缓存最大条目数，超限按 evictionPolicy 淘汰 */
    private int cacheCapacity = 50;
    /** 缓存淘汰策略：LRU(最近最少使用) 或 FIFO(先进先出)，无法识别时启动即失败 */
    private String cacheEvictionPolicy = "LRU";

    public int getMaxRuns() { return maxRuns; }
    public void setMaxRuns(int maxRuns) { this.maxRuns = maxRuns; }
    public int getMaxArtifactsPerPipeline() { return maxArtifactsPerPipeline; }
    public void setMaxArtifactsPerPipeline(int maxArtifactsPerPipeline) { this.maxArtifactsPerPipeline = maxArtifactsPerPipeline; }
    public int getCacheCapacity() { return cacheCapacity; }
    public void setCacheCapacity(int cacheCapacity) { this.cacheCapacity = cacheCapacity; }
    public String getCacheEvictionPolicy() { return cacheEvictionPolicy; }
    public void setCacheEvictionPolicy(String cacheEvictionPolicy) { this.cacheEvictionPolicy = cacheEvictionPolicy; }
}
