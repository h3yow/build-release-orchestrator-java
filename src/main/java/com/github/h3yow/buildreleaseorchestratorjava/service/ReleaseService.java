package com.github.h3yow.buildreleaseorchestratorjava.service;

import org.springframework.stereotype.Service;

/**
 * 发布指针与回滚：记录每条流水线当前发布版本，回滚幂等且校验目标制品可用。
 */
@Service
public class ReleaseService {

    public static class ReleaseState {
        private final String pipelineId;
        private final String currentVersion;
        private final boolean changed;

        public ReleaseState(String pipelineId, String currentVersion, boolean changed) {
            this.pipelineId = pipelineId;
            this.currentVersion = currentVersion;
            this.changed = changed;
        }

        public String getPipelineId() { return pipelineId; }
        public String getCurrentVersion() { return currentVersion; }
        public boolean isChanged() { return changed; }
    }

    /**
     * 将当前发布指针推进到指定版本（发布成功后调用）。
     */
    public ReleaseState release(String pipelineId, String version) {
        // TODO: 实现
        return null;
    }

    /**
     * 回滚到历史版本：幂等；目标不存在或校验和不一致必须拒绝。
     */
    public ReleaseState rollback(String pipelineId, String targetVersion) {
        // TODO: 实现
        return null;
    }

    public String currentVersion(String pipelineId) {
        // TODO: 实现
        return null;
    }
}
