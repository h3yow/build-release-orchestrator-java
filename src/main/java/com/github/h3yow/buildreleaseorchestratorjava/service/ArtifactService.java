package com.github.h3yow.buildreleaseorchestratorjava.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.domain.Artifact;
import com.github.h3yow.buildreleaseorchestratorjava.exception.ArtifactNotFoundException;
import com.github.h3yow.buildreleaseorchestratorjava.exception.LimitExceededException;
import com.github.h3yow.buildreleaseorchestratorjava.exception.VersionConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 制品与版本管理：版本单调推进、同名冲突拒绝、保留量淘汰、校验和可追溯。
 * 每条流水线一把锁，保证并发注册时版本不跳变、无半更新状态。
 */
@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    private final OrchestratorProperties properties;
    /** pipelineId -> (version -> artifact) */
    private final Map<String, Map<String, Artifact>> artifacts = new ConcurrentHashMap<>();
    /** pipelineId -> 版本计数器，版本规则为 1.0.N，单调递增 */
    private final Map<String, AtomicInteger> versionCounters = new ConcurrentHashMap<>();
    /** pipelineId -> 流水线级锁 */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    /** 当前发布版本提供者（由 ReleaseService 注入），淘汰时保护当前发布版本 */
    private volatile Function<String, String> currentVersionProvider = pipelineId -> null;

    public ArtifactService(OrchestratorProperties properties) {
        this.properties = properties;
    }

    public void setCurrentVersionProvider(Function<String, String> provider) {
        this.currentVersionProvider = provider;
    }

    /**
     * 预览下一个版本号（不消费计数器）。
     */
    public String nextVersion(String pipelineId) {
        int next = versionCounters.computeIfAbsent(pipelineId, k -> new AtomicInteger(0)).get() + 1;
        return "1.0." + next;
    }

    /**
     * 分配下一个版本并原子注册制品：版本推进与制品落库在同一临界区，避免版本跳变与半更新。
     */
    public Artifact registerNext(String pipelineId, String content, String source) {
        synchronized (lockOf(pipelineId)) {
            String version = nextVersion(pipelineId);
            Artifact artifact = doRegister(pipelineId, version, content, source);
            versionCounters.get(pipelineId).incrementAndGet();
            return artifact;
        }
    }

    /**
     * 注册指定版本制品：同名版本冲突必须拒绝并区分原因；超保留量按策略淘汰。
     */
    public Artifact register(String pipelineId, String version, String content, String source) {
        synchronized (lockOf(pipelineId)) {
            return doRegister(pipelineId, version, content, source);
        }
    }

    private Artifact doRegister(String pipelineId, String version, String content, String source) {
        String checksum = sha256(content);
        Map<String, Artifact> byVersion = artifacts.computeIfAbsent(pipelineId, k -> new ConcurrentHashMap<>());
        Artifact existing = byVersion.get(version);
        if (existing != null) {
            if (existing.getChecksum().equals(checksum)) {
                log.warn("[artifact] 版本冲突: pipeline={}, version={}, 原因=版本已存在且校验和一致({})",
                        pipelineId, version, checksum);
                throw new VersionConflictException(VersionConflictException.VERSION_ALREADY_EXISTS,
                        "版本已存在: pipeline=" + pipelineId + ", version=" + version + ", checksum=" + checksum);
            }
            log.warn("[artifact] 版本冲突: pipeline={}, version={}, 原因=校验和不一致(已登记={}, 本次={})",
                    pipelineId, version, existing.getChecksum(), checksum);
            throw new VersionConflictException(VersionConflictException.CHECKSUM_MISMATCH,
                    "版本已存在但校验和不一致: pipeline=" + pipelineId + ", version=" + version
                            + ", existing=" + existing.getChecksum() + ", incoming=" + checksum);
        }
        Artifact artifact = new Artifact(pipelineId, version, checksum, source, content, Instant.now());
        byVersion.put(version, artifact);
        log.info("[artifact] 注册制品: pipeline={}, version={}, checksum={}, source={}",
                pipelineId, version, checksum, source);
        try {
            evictIfNeeded(pipelineId, byVersion);
        } catch (LimitExceededException e) {
            // 回滚本次注册，避免半更新状态
            byVersion.remove(version);
            throw e;
        }
        return artifact;
    }

    /** 保留量淘汰：淘汰最旧的非当前发布制品；无可淘汰则拒绝。 */
    private void evictIfNeeded(String pipelineId, Map<String, Artifact> byVersion) {
        int max = properties.getMaxArtifactsPerPipeline();
        while (byVersion.size() > max) {
            String currentVersion = currentVersionProvider.apply(pipelineId);
            Artifact oldest = byVersion.values().stream()
                    .filter(a -> !a.getVersion().equals(currentVersion))
                    .min(Comparator.comparing(Artifact::getCreatedAt))
                    .orElse(null);
            if (oldest == null) {
                log.warn("[artifact] 制品保留量超限且无可淘汰项: pipeline={}, max={}, 拒绝注册", pipelineId, max);
                throw new LimitExceededException(
                        "制品保留量超限且无可淘汰项: pipeline=" + pipelineId + ", max=" + max);
            }
            byVersion.remove(oldest.getVersion());
            log.info("[artifact] 淘汰最旧制品: pipeline={}, version={}, max={}",
                    pipelineId, oldest.getVersion(), max);
        }
    }

    public Artifact get(String pipelineId, String version) {
        Map<String, Artifact> byVersion = artifacts.get(pipelineId);
        Artifact artifact = byVersion == null ? null : byVersion.get(version);
        if (artifact == null) {
            throw new ArtifactNotFoundException("制品不存在: pipeline=" + pipelineId + ", version=" + version);
        }
        return artifact;
    }

    public List<Artifact> list(String pipelineId) {
        Map<String, Artifact> byVersion = artifacts.get(pipelineId);
        if (byVersion == null) {
            return List.of();
        }
        List<Artifact> list = new ArrayList<>(byVersion.values());
        list.sort(Comparator.comparing(Artifact::getCreatedAt));
        return list;
    }

    /**
     * 校验制品当前内容与登记校验和是否一致（用于回滚前可用性校验）。
     */
    public boolean verifyChecksum(String pipelineId, String version) {
        Artifact artifact = get(pipelineId, version);
        String actual = sha256(artifact.getContent());
        boolean ok = actual.equals(artifact.getChecksum());
        if (!ok) {
            log.warn("[artifact] 校验和不一致: pipeline={}, version={}, 登记={}, 实际={}",
                    pipelineId, version, artifact.getChecksum(), actual);
        }
        return ok;
    }

    /**
     * 本地模拟钩子：篡改制品内容以模拟制品损坏（仅用于测试与本地验证）。
     */
    public void simulateCorruption(String pipelineId, String version) {
        synchronized (lockOf(pipelineId)) {
            Artifact artifact = get(pipelineId, version);
            Map<String, Artifact> byVersion = artifacts.get(pipelineId);
            byVersion.put(version, new Artifact(artifact.getPipelineId(), artifact.getVersion(),
                    artifact.getChecksum(), artifact.getSource(),
                    artifact.getContent() + "-corrupted", artifact.getCreatedAt()));
            log.warn("[artifact] 模拟制品损坏: pipeline={}, version={}", pipelineId, version);
        }
    }

    static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private Object lockOf(String pipelineId) {
        return locks.computeIfAbsent(pipelineId, k -> new Object());
    }
}
