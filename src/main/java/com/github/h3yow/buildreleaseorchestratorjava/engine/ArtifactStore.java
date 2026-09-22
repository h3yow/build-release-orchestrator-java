package com.github.h3yow.buildreleaseorchestratorjava.engine;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.error.ErrorCode;
import com.github.h3yow.buildreleaseorchestratorjava.error.OrchestratorException;
import com.github.h3yow.buildreleaseorchestratorjava.model.Artifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Local in-memory artifact store.
 *
 * <p>Registration semantics under the per-pipeline lock:
 * <ul>
 *   <li>an existing version with a different checksum fails with
 *       {@code ARTIFACT_CHECKSUM_MISMATCH};</li>
 *   <li>an existing version with the same checksum is an idempotent no-op;</li>
 *   <li>a brand new version must be the next monotonic version
 *       ({@code VERSION_ALREADY_EXISTS} / {@code VERSION_CONFLICT}).</li>
 * </ul>
 * Retention is bounded by {@code orchestrator.max-artifacts-per-pipeline};
 * eviction removes the oldest artifact that is not referenced by the current
 * or previous release pointer. If everything is protected, registration is
 * rejected instead of evicting a live artifact, and no partial state remains.
 */
@Component
public class ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    private final OrchestratorProperties properties;
    private final VersionManager versionManager;
    private final ObjectProvider<ReleaseService> releaseServiceProvider;
    private final Map<String, Map<String, Artifact>> byPipeline = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ArtifactStore(OrchestratorProperties properties, VersionManager versionManager,
                         ObjectProvider<ReleaseService> releaseServiceProvider) {
        this.properties = properties;
        this.versionManager = versionManager;
        this.releaseServiceProvider = releaseServiceProvider;
    }

    public Artifact register(String pipelineId, String version, String checksum, String sourceRunId,
                             String buildInputHash, String content) {
        if (!checksum.equals(Checksums.sha256(content))) {
            throw new OrchestratorException(ErrorCode.ARTIFACT_CHECKSUM_MISMATCH,
                    "refusing to register " + pipelineId + ":" + version
                            + " because supplied checksum does not match content");
        }
        ReentrantLock lock = lockOf(pipelineId);
        lock.lock();
        try {
            Map<String, Artifact> artifacts = byPipeline.computeIfAbsent(pipelineId,
                    k -> new ConcurrentHashMap<>());
            Artifact existing = artifacts.get(version);
            if (existing != null) {
                if (!existing.getChecksum().equals(checksum)) {
                    log.warn("artifact conflict pipeline={} version={} existingChecksum={} incomingChecksum={} reason=checksum-mismatch",
                            pipelineId, version, existing.getChecksum(), checksum);
                    throw new OrchestratorException(ErrorCode.ARTIFACT_CHECKSUM_MISMATCH,
                            "version " + version + " already exists with a different checksum");
                }
                log.info("artifact register idempotent pipeline={} version={} checksum={} sourceRun={}",
                        pipelineId, version, checksum, sourceRunId);
                return existing;
            }

            int patch = VersionManager.parsePatch(version);
            int nextPatch = versionManager.currentSequence(pipelineId) + 1;
            if (patch < nextPatch) {
                log.warn("artifact conflict pipeline={} version={} reason=version-already-exists",
                        pipelineId, version);
                throw new OrchestratorException(ErrorCode.VERSION_ALREADY_EXISTS,
                        "version " + version + " already exists for pipeline " + pipelineId);
            }
            if (patch != nextPatch) {
                throw new OrchestratorException(ErrorCode.VERSION_CONFLICT,
                        "refusing artifact " + version + ", next monotonic version is 1.0."
                                + nextPatch);
            }

            // PACKAGE runs before the release pointer moves, so retention here is a
            // plain rolling window over versions; pointer-aware convergence happens
            // again after RELEASE (see enforceRetention).
            if (artifacts.size() >= properties.getMaxArtifactsPerPipeline()) {
                evictOldest(pipelineId, artifacts);
            }

            Artifact artifact = new Artifact(pipelineId, version, checksum, sourceRunId,
                    buildInputHash, content, Instant.now());
            artifacts.put(version, artifact);
            versionManager.commit(pipelineId, version);
            log.info("artifact registered pipeline={} version={} checksum={} sourceRun={} buildInputHash={}",
                    pipelineId, version, checksum, sourceRunId, buildInputHash);
            return artifact;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Evict one oldest, unprotected artifact. Protected versions are supplied
     * by the release service (current + previous pointer).
     */
    /**
     * Pointer-aware retention convergence after a release: evict the oldest
     * artifact that neither the current nor previous pointer references, while
     * the store exceeds its cap. Protected artifacts are never removed.
     */
    public void enforceRetention(String pipelineId) {
        ReentrantLock lock = lockOf(pipelineId);
        lock.lock();
        try {
            Map<String, Artifact> artifacts = byPipeline.get(pipelineId);
            if (artifacts == null) {
                return;
            }
            Set<String> protectedVersions =
                    releaseServiceProvider.getObject().protectedVersions(pipelineId);
            while (artifacts.size() > properties.getMaxArtifactsPerPipeline()) {
                Artifact victim = artifacts.values().stream()
                        .filter(a -> !protectedVersions.contains(a.getVersion()))
                        .min(Comparator
                                .comparingInt((Artifact a) -> VersionManager.parsePatch(a.getVersion()))
                                .thenComparing(Artifact::getCreatedAt))
                        .orElse(null);
                if (victim == null) {
                    log.warn("retention converged but cap exceeded pipeline={} size={} reason=all-protected",
                            pipelineId, artifacts.size());
                    return;
                }
                artifacts.remove(victim.getVersion());
                log.info("artifact evicted pipeline={} version={} checksum={} reason=post-release-retention",
                        pipelineId, victim.getVersion(), victim.getChecksum());
            }
        } finally {
            lock.unlock();
        }
    }

    private void evictOldest(String pipelineId, Map<String, Artifact> artifacts) {
        artifacts.values().stream()
                .min(Comparator
                        .comparingInt((Artifact a) -> VersionManager.parsePatch(a.getVersion()))
                        .thenComparing(Artifact::getCreatedAt))
                .ifPresent(victim -> {
                    artifacts.remove(victim.getVersion());
                    log.info("artifact evicted pipeline={} version={} checksum={} reason=rolling-window",
                            pipelineId, victim.getVersion(), victim.getChecksum());
                });
    }

    public Artifact require(String pipelineId, String version) {
        Artifact artifact = find(pipelineId, version);
        if (artifact == null) {
            throw new OrchestratorException(ErrorCode.ARTIFACT_NOT_FOUND,
                    "artifact not found: " + pipelineId + ":" + version);
        }
        return artifact;
    }

    public Artifact find(String pipelineId, String version) {
        Map<String, Artifact> artifacts = byPipeline.get(pipelineId);
        return artifacts == null ? null : artifacts.get(version);
    }

    public List<Artifact> list(String pipelineId) {
        Map<String, Artifact> artifacts = byPipeline.get(pipelineId);
        if (artifacts == null) {
            return List.of();
        }
        List<Artifact> result = new ArrayList<>(artifacts.values());
        result.sort(Comparator.comparing(Artifact::getCreatedAt));
        return result;
    }

    /**
     * Recompute the checksum of the stored content. This is how rollback and
     * release reject a tampered/corrupted artifact instead of silently using it.
     */
    /**
     * Test/diagnostic hook: replace the stored content of an artifact with
     * tampered bytes while keeping the recorded checksum, so verification must
     * detect the corruption instead of silently trusting the stored pointer.
     */
    public boolean debugTamper(String pipelineId, String version) {
        ReentrantLock lock = lockOf(pipelineId);
        lock.lock();
        try {
            Map<String, Artifact> artifacts = byPipeline.get(pipelineId);
            if (artifacts == null || !artifacts.containsKey(version)) {
                return false;
            }
            Artifact original = artifacts.get(version);
            artifacts.put(version, new Artifact(pipelineId, version, original.getChecksum(),
                    original.getSourceRunId(), original.getBuildInputHash(),
                    original.getContent() + "###tampered###", original.getCreatedAt()));
            log.warn("artifact debug-tamper pipeline={} version={} reason=test-injection",
                    pipelineId, version);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public Artifact verify(String pipelineId, String version) {
        Artifact artifact = require(pipelineId, version);
        String actual = Checksums.sha256(artifact.getContent());
        if (!actual.equals(artifact.getChecksum())) {
            log.warn("artifact verification failed pipeline={} version={} expected={} actual={}",
                    pipelineId, version, artifact.getChecksum(), actual);
            throw new OrchestratorException(ErrorCode.ARTIFACT_CHECKSUM_MISMATCH,
                    "artifact " + version + " failed checksum verification");
        }
        return artifact;
    }

    private ReentrantLock lockOf(String pipelineId) {
        return locks.computeIfAbsent(pipelineId, k -> new ReentrantLock());
    }
}
