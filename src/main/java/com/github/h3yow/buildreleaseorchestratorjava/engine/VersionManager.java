package com.github.h3yow.buildreleaseorchestratorjava.engine;

import com.github.h3yow.buildreleaseorchestratorjava.error.ErrorCode;
import com.github.h3yow.buildreleaseorchestratorjava.error.OrchestratorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Monotonic semantic-style version allocation.
 *
 * <p>Versions have the form {@code 1.0.N} where {@code N} starts at 1. The
 * per-pipeline sequence is advanced in two steps: {@link #allocate} reserves a
 * candidate version and {@link #commit} confirms it once the artifact has been
 * registered. A run that fails before producing an artifact therefore never
 * consumes a version, so failed attempts cannot create gaps. All callers are
 * expected to hold the per-pipeline orchestration lock, which makes the
 * allocate/commit pair atomic relative to other triggers of the same pipeline.
 */
@Component
public class VersionManager {

    private static final Logger log = LoggerFactory.getLogger(VersionManager.class);

    private final Map<String, AtomicInteger> sequenceByPipeline = new ConcurrentHashMap<>();

    public String allocate(String pipelineId, String requestedVersion) {
        AtomicInteger currentCounter = sequenceByPipeline.get(pipelineId);
        int current = currentCounter == null ? 0 : currentCounter.get();
        int next = current + 1;
        if (requestedVersion != null && !requestedVersion.isBlank()) {
            int requestedPatch;
            try {
                requestedPatch = parsePatch(requestedVersion.trim());
            } catch (OrchestratorException e) {
                throw e;
            }
            if (requestedPatch != next) {
                log.warn("version rejected pipeline={} requested={} expected=1.0.{} reason=non-monotonic",
                        pipelineId, requestedVersion, next);
                throw new OrchestratorException(ErrorCode.VERSION_CONFLICT,
                        "requested version " + requestedVersion
                                + " is not the next monotonic version, expected 1.0." + next);
            }
            log.info("version allocated pipeline={} version={} source=requested",
                    pipelineId, requestedVersion.trim());
            return format(requestedPatch);
        }
        log.info("version allocated pipeline={} version=1.0.{} source=auto", pipelineId, next);
        return format(next);
    }

    public void commit(String pipelineId, String version) {
        int patch = parsePatch(version);
        AtomicInteger sequence = sequenceByPipeline.computeIfAbsent(pipelineId,
                k -> new AtomicInteger(0));
        int before = sequence.get();
        if (patch <= before) {
            // Already committed, e.g. idempotent re-entry: do not move the pointer backwards.
            log.info("version commit skipped pipeline={} version={} currentPatch={} reason=already-committed",
                    pipelineId, version, before);
            return;
        }
        if (patch != before + 1) {
            throw new OrchestratorException(ErrorCode.VERSION_CONFLICT,
                    "refusing to commit version " + version + " out of order, current=1.0." + before);
        }
        sequence.set(patch);
        log.info("version committed pipeline={} version=1.0.{}", pipelineId, patch);
    }

    public int currentSequence(String pipelineId) {
        AtomicInteger sequence = sequenceByPipeline.get(pipelineId);
        return sequence == null ? 0 : sequence.get();
    }

    public static int compareVersions(String a, String b) {
        return Integer.compare(parsePatch(a), parsePatch(b));
    }

    static int parsePatch(String version) {
        if (version == null || !version.startsWith("1.0.")) {
            throw new OrchestratorException(ErrorCode.VERSION_CONFLICT,
                    "illegal version (expected 1.0.N): " + version);
        }
        String patchText = version.substring("1.0.".length());
        try {
            int patch = Integer.parseInt(patchText);
            if (patch <= 0) {
                throw new NumberFormatException("non-positive patch");
            }
            return patch;
        } catch (NumberFormatException e) {
            throw new OrchestratorException(ErrorCode.VERSION_CONFLICT,
                    "illegal version (expected 1.0.N): " + version);
        }
    }

    static String format(int sequence) {
        return "1.0." + sequence;
    }
}
