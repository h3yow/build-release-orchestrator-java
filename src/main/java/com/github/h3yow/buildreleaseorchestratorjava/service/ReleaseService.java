package com.github.h3yow.buildreleaseorchestratorjava.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.h3yow.buildreleaseorchestratorjava.exception.ArtifactNotFoundException;
import com.github.h3yow.buildreleaseorchestratorjava.exception.RollbackRejectedException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 发布指针与回滚：记录每条流水线当前发布版本，回滚幂等且校验目标制品可用。
 */
@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    private final ArtifactService artifactService;
    /** pipelineId -> 当前发布版本 */
    private final Map<String, String> currentVersions = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public ReleaseService(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

    @PostConstruct
    void wireCurrentVersionProvider() {
        // 供制品淘汰策略保护当前发布版本
        artifactService.setCurrentVersionProvider(this::currentVersion);
    }

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
        synchronized (lockOf(pipelineId)) {
            String prev = currentVersions.get(pipelineId);
            currentVersions.put(pipelineId, version);
            log.info("[release] 发布指针推进: pipeline={}, {} -> {}", pipelineId, prev, version);
            return new ReleaseState(pipelineId, version, !version.equals(prev));
        }
    }

    /**
     * 回滚到历史版本：幂等；目标不存在或校验和不一致必须拒绝。
     */
    public ReleaseState rollback(String pipelineId, String targetVersion) {
        synchronized (lockOf(pipelineId)) {
            String current = currentVersions.get(pipelineId);
            log.info("[release] 回滚请求: pipeline={}, 当前版本={}, 目标版本={}", pipelineId, current, targetVersion);
            if (targetVersion == null || targetVersion.isBlank()) {
                throw new RollbackRejectedException("INVALID_TARGET", "回滚目标版本为空: pipeline=" + pipelineId);
            }
            if (targetVersion.equals(current)) {
                log.info("[release] 幂等回滚: pipeline={}, 目标版本{}已是当前版本，不产生变更", pipelineId, targetVersion);
                return new ReleaseState(pipelineId, current, false);
            }
            try {
                artifactService.get(pipelineId, targetVersion);
            } catch (ArtifactNotFoundException e) {
                log.warn("[release] 回滚被拒绝: pipeline={}, target={}, 原因=目标制品不存在", pipelineId, targetVersion);
                throw new RollbackRejectedException("ARTIFACT_NOT_FOUND",
                        "回滚目标制品不存在: pipeline=" + pipelineId + ", version=" + targetVersion);
            }
            if (!artifactService.verifyChecksum(pipelineId, targetVersion)) {
                log.warn("[release] 回滚被拒绝: pipeline={}, target={}, 原因=目标制品校验和不一致", pipelineId, targetVersion);
                throw new RollbackRejectedException("CHECKSUM_MISMATCH",
                        "回滚目标制品不可用(校验和不一致): pipeline=" + pipelineId + ", version=" + targetVersion);
            }
            currentVersions.put(pipelineId, targetVersion);
            log.info("[release] 回滚完成: pipeline={}, {} -> {}", pipelineId, current, targetVersion);
            return new ReleaseState(pipelineId, targetVersion, true);
        }
    }

    public String currentVersion(String pipelineId) {
        return currentVersions.get(pipelineId);
    }

    private Object lockOf(String pipelineId) {
        return locks.computeIfAbsent(pipelineId, k -> new Object());
    }
}
