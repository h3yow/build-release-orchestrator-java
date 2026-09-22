package com.github.h3yow.buildreleaseorchestratorjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orchestrator")
public class OrchestratorProperties {

    private int maxRunsPerPipeline = 100;
    private int maxArtifactsPerPipeline = 20;
    private int maxCacheEntries = 50;
    private String cacheEvictionPolicy = "LRU";

    public int getMaxRunsPerPipeline() {
        return maxRunsPerPipeline;
    }

    public void setMaxRunsPerPipeline(int maxRunsPerPipeline) {
        this.maxRunsPerPipeline = maxRunsPerPipeline;
    }

    public int getMaxArtifactsPerPipeline() {
        return maxArtifactsPerPipeline;
    }

    public void setMaxArtifactsPerPipeline(int maxArtifactsPerPipeline) {
        this.maxArtifactsPerPipeline = maxArtifactsPerPipeline;
    }

    public int getMaxCacheEntries() {
        return maxCacheEntries;
    }

    public void setMaxCacheEntries(int maxCacheEntries) {
        this.maxCacheEntries = maxCacheEntries;
    }

    public String getCacheEvictionPolicy() {
        return cacheEvictionPolicy;
    }

    public void setCacheEvictionPolicy(String cacheEvictionPolicy) {
        this.cacheEvictionPolicy = cacheEvictionPolicy;
    }
}
