package com.github.h3yow.buildreleaseorchestratorjava.engine;

import com.github.h3yow.buildreleaseorchestratorjava.error.ErrorCode;
import com.github.h3yow.buildreleaseorchestratorjava.error.OrchestratorException;
import com.github.h3yow.buildreleaseorchestratorjava.model.Artifact;
import com.github.h3yow.buildreleaseorchestratorjava.model.ReleaseState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the released "current" pointer per pipeline.
 *
 * <p>Every pointer move is preceded by artifact verification (existence +
 * checksum), so a failed publish/rollback leaves the previous pointer intact
 * (no half-updated state). Rollback is idempotent: rolling back to the version
 * that is already current is a no-op and still succeeds. Rolling back to an
 * unknown, evicted or corrupted artifact is rejected with
 * {@code ROLLBACK_TARGET_UNAVAILABLE} / {@code ARTIFACT_CHECKSUM_MISMATCH}.
 */
@Service
public class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    private final ArtifactStore artifactStore;
    private final Map<String, ReleaseState> states = new ConcurrentHashMap<>();

    public ReleaseService(ArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    public ReleaseState publish(String pipelineId, Artifact artifact, String runId) {
        // Verify before touching the pointer so a corrupted artifact cannot half-update it.
        Artifact verified = artifactStore.verify(pipelineId, artifact.getVersion());
        synchronized (lockOf(pipelineId)) {
            ReleaseState state = states.computeIfAbsent(pipelineId, ReleaseState::new);
            String currentVersion = state.getCurrentVersion();
            if (artifact.getVersion().equals(currentVersion)
                    && artifact.getChecksum().equals(state.getCurrentChecksum())) {
                log.info("publish idempotent pipeline={} version={} checksum={} runId={}",
                        pipelineId, artifact.getVersion(), artifact.getChecksum(), runId);
                state.setLastRunId(runId);
                state.setUpdatedAt(Instant.now());
                return state;
            }
            if (currentVersion != null
                    && VersionManager.compareVersions(artifact.getVersion(), currentVersion) < 0) {
                throw new OrchestratorException(ErrorCode.VERSION_CONFLICT,
                        "refusing to publish older version " + artifact.getVersion()
                                + " over current " + currentVersion);
            }
            state.setPreviousVersion(currentVersion);
            state.setPreviousChecksum(state.getCurrentChecksum());
            state.setCurrentVersion(verified.getVersion());
            state.setCurrentChecksum(verified.getChecksum());
            state.setLastRunId(runId);
            state.setUpdatedAt(Instant.now());
            log.info("publish pointer moved pipeline={} from={} to={} checksum={} runId={}",
                    pipelineId, currentVersion, verified.getVersion(),
                    verified.getChecksum(), runId);
            return state;
        }
    }

    public ReleaseState rollback(String pipelineId, String targetVersion) {
        synchronized (lockOf(pipelineId)) {
            ReleaseState state = states.computeIfAbsent(pipelineId, ReleaseState::new);
            String desired = (targetVersion == null || targetVersion.isBlank())
                    ? state.getPreviousVersion() : targetVersion.trim();
            if (desired == null) {
                throw new OrchestratorException(ErrorCode.ROLLBACK_TARGET_UNAVAILABLE,
                        "no previous release exists to roll back to for pipeline " + pipelineId);
            }
            if (desired.equals(state.getCurrentVersion())) {
                log.info("rollback idempotent pipeline={} version={} checksum={}",
                        pipelineId, desired, state.getCurrentChecksum());
                state.setUpdatedAt(Instant.now());
                return state;
            }
            Artifact target;
            try {
                target = artifactStore.verify(pipelineId, desired);
            } catch (OrchestratorException e) {
                if (e.getErrorCode() == ErrorCode.ARTIFACT_NOT_FOUND) {
                    throw new OrchestratorException(ErrorCode.ROLLBACK_TARGET_UNAVAILABLE,
                            "cannot roll back to " + desired + ": artifact is unavailable");
                }
                throw e;
            }
            String previousVersion = state.getCurrentVersion();
            String previousChecksum = state.getCurrentChecksum();
            state.setCurrentVersion(target.getVersion());
            state.setCurrentChecksum(target.getChecksum());
            state.setPreviousVersion(previousVersion);
            state.setPreviousChecksum(previousChecksum);
            state.setUpdatedAt(Instant.now());
            log.warn("rollback pipeline={} from={} to={} checksum={}",
                    pipelineId, previousVersion, target.getVersion(), target.getChecksum());
            return state;
        }
    }

    public Set<String> protectedVersions(String pipelineId) {
        ReleaseState state = states.get(pipelineId);
        if (state == null) {
            return Set.of();
        }
        Set<String> protectedVersions = ConcurrentHashMap.newKeySet();
        if (state.getCurrentVersion() != null) {
            protectedVersions.add(state.getCurrentVersion());
        }
        if (state.getPreviousVersion() != null) {
            protectedVersions.add(state.getPreviousVersion());
        }
        return protectedVersions;
    }

    public ReleaseState get(String pipelineId) {
        return states.computeIfAbsent(pipelineId, ReleaseState::new);
    }

    public List<ReleaseState> list() {
        return List.copyOf(states.values());
    }

    private Object lockOf(String pipelineId) {
        return states.computeIfAbsent(pipelineId, ReleaseState::new);
    }
}
