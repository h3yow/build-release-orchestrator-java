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
     * 预览下一个版本号（不消费版本号）：从自动版本水位之后寻找首个未被占用的 1.0.N，
     * 与 {@link #registerNext} 的实际分配保持一致。
     */
    public String nextVersion(String pipelineId) {
        Map<String, Artifact> byVersion = artifacts.get(pipelineId);
        int next = versionCounters.computeIfAbsent(pipelineId, k -> new AtomicInteger(0)).get() + 1;
        while (byVersion != null && byVersion.containsKey("1.0." + next)) {
            next++;
        }
        return "1.0." + next;
    }

    /**
     * 分配下一个版本并原子注册制品：版本推进与制品落库在同一临界区，避免版本跳变与半更新。
     * 自动版本水位会在每次成功注册后单调推进；若水位指向的 1.0.N 已被“指定版本”占用
     * （或因历史原因已登记），则继续向后寻找首个未占用号，保证普通触发永不与指定版本撞号。
     */
    public Artifact registerNext(String pipelineId, String content, String source) {
        synchronized (lockOf(pipelineId)) {
            Map<String, Artifact> byVersion = artifacts.computeIfAbsent(pipelineId, k -> new ConcurrentHashMap<>());
            AtomicInteger counter = versionCounters.computeIfAbsent(pipelineId, k -> new AtomicInteger(0));
            String version;
            do {
                version = "1.0." + (counter.get() + 1);
            } while (byVersion.containsKey(version));
            return doRegister(pipelineId, byVersion, version, content, source);
        }
    }

    /**
     * 注册指定版本制品：同名版本冲突必须拒绝并区分原因；超保留量按策略淘汰。
     * 注册成功后，若版本落在自动号段 1.0.N，则把自动版本水位推进到 N（单调、不回退），
     * 从而保证后续普通触发拿到的版本不会与该指定版本冲突；冲突拒绝时不推进水位。
     */
    public Artifact register(String pipelineId, String version, String content, String source) {
        synchronized (lockOf(pipelineId)) {
            Map<String, Artifact> byVersion = artifacts.computeIfAbsent(pipelineId, k -> new ConcurrentHashMap<>());
            return doRegister(pipelineId, byVersion, version, content, source);
        }
    }

    private Artifact doRegister(String pipelineId, Map<String, Artifact> byVersion, String version,
                                String content, String source) {
        String checksum = sha256(content);
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
            evictIfNeeded(pipelineId, byVersion, version);
        } catch (LimitExceededException e) {
            // 回滚本次注册，避免半更新状态
            byVersion.remove(version);
            throw e;
        }
        // 注册（含淘汰）成功后再推进自动版本水位：冲突拒绝或超限回滚都不会推进水位。
        // 仅自动号段 1.0.N 参与水位，单调不回退，保证后续普通触发永不复用该版本号。
        advanceAutoVersionWatermark(pipelineId, version);
        return artifact;
    }

    /** 若版本属于自动号段 1.0.N，把该流水线的自动版本水位单调推进到 N。 */
    private void advanceAutoVersionWatermark(String pipelineId, String version) {
        String prefix = "1.0.";
        if (version == null || !version.startsWith(prefix)) {
            return;
        }
        String suffix = version.substring(prefix.length());
        if (suffix.isEmpty()) {
            return;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) {
                return;
            }
        }
        int n;
        try {
            n = Integer.parseInt(suffix);
        } catch (NumberFormatException e) {
            return;
        }
        if (n <= 0) {
            return;
        }
        versionCounters.computeIfAbsent(pipelineId, k -> new AtomicInteger(0))
                .accumulateAndGet(n, Math::max);
    }

    /** 保留量淘汰：淘汰最旧的非当前发布、非本次新注册制品；无可淘汰则拒绝。 */
    private void evictIfNeeded(String pipelineId, Map<String, Artifact> byVersion, String newVersion) {
        int max = properties.getMaxArtifactsPerPipeline();
        while (byVersion.size() > max) {
            String currentVersion = currentVersionProvider.apply(pipelineId);
            Artifact oldest = byVersion.values().stream()
                    .filter(a -> !a.getVersion().equals(currentVersion))
                    .filter(a -> !a.getVersion().equals(newVersion))
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

    public static String sha256(String content) {
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
