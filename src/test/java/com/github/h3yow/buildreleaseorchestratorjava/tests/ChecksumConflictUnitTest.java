package com.github.h3yow.buildreleaseorchestratorjava.tests;

import com.github.h3yow.buildreleaseorchestratorjava.engine.ArtifactStore;
import com.github.h3yow.buildreleaseorchestratorjava.engine.Checksums;
import com.github.h3yow.buildreleaseorchestratorjava.engine.OrchestratorService;
import com.github.h3yow.buildreleaseorchestratorjava.engine.ReleaseService;
import com.github.h3yow.buildreleaseorchestratorjava.error.ErrorCode;
import com.github.h3yow.buildreleaseorchestratorjava.error.OrchestratorException;
import com.github.h3yow.buildreleaseorchestratorjava.model.Artifact;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
public class ChecksumConflictUnitTest {

    private static final Logger log = LoggerFactory.getLogger(ChecksumConflictUnitTest.class);

    @Autowired
    private ArtifactStore artifactStore;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private OrchestratorService orchestrator;

    @Test
    void sameVersionDifferentChecksumRejectedAndDistinguishedFromMissingVersion() {
        String pipelineId = "checksum-unit-pipeline";
        orchestrator.registerPipeline(pipelineId, "checksum",
                List.of("CHECKOUT", "BUILD", "TEST", "PACKAGE", "RELEASE"));

        // Produce 1.0.1 through a real run.
        var request = new com.github.h3yow.buildreleaseorchestratorjava.web.TriggerRequest();
        request.setIdempotencyKey("idem-checksum-1");
        request.setStageDelayMillis(1L);
        var result = orchestrator.trigger(pipelineId, request);
        Artifact existing = artifactStore.require(pipelineId, "1.0.1");
        log.info("SETUP version=1.0.1 checksum={}", existing.getChecksum());

        // Attempting to register 1.0.1 again with a DIFFERENT content/checksum must be
        // rejected as ARTIFACT_CHECKSUM_MISMATCH, not VERSION_ALREADY_EXISTS.
        String differentContent = "artifact|checksum-unit-pipeline|1.0.1|different-input";
        String differentChecksum = Checksums.sha256(differentContent);
        OrchestratorException mismatch = assertThrows(OrchestratorException.class,
                () -> artifactStore.register(pipelineId, "1.0.1", differentChecksum,
                        "run-forged", "different-input", differentContent));
        log.info("DECISION same-version different-checksum rejected errorCode={} message={}",
                mismatch.getErrorCode(), mismatch.getMessage());
        assertEquals(ErrorCode.ARTIFACT_CHECKSUM_MISMATCH, mismatch.getErrorCode());

        // An unknown version is a distinct reason.
        OrchestratorException missing = assertThrows(OrchestratorException.class,
                () -> artifactStore.require(pipelineId, "1.0.42"));
        log.info("DECISION unknown version rejected errorCode={}", missing.getErrorCode());
        assertEquals(ErrorCode.ARTIFACT_NOT_FOUND, missing.getErrorCode());

        // Registering again with the SAME content/checksum is idempotent: no duplicate.
        Artifact again = artifactStore.register(pipelineId, "1.0.1", existing.getChecksum(),
                "run-duplicate", existing.getBuildInputHash(), existing.getContent());
        assertEquals(existing.getChecksum(), again.getChecksum());
        assertEquals(existing.getSourceRunId(), again.getSourceRunId(),
                "idempotent re-register returns the original provenance");
        log.info("DECISION same-version same-checksum idempotent sourceRun preserved={}",
                again.getSourceRunId());

        // A supplied checksum that does not even match the supplied content is rejected.
        OrchestratorException bogus = assertThrows(OrchestratorException.class,
                () -> artifactStore.register(pipelineId, "1.0.2", "deadbeef",
                        "run-bogus", "h", "content-not-matching"));
        assertEquals(ErrorCode.ARTIFACT_CHECKSUM_MISMATCH, bogus.getErrorCode());
        log.info("DECISION content/checksum disagreement rejected errorCode={}", bogus.getErrorCode());

        // Existing artifact remains intact and release verifies it.
        Artifact verified = artifactStore.verify(pipelineId, result.getVersion());
        assertEquals(existing.getChecksum(), verified.getChecksum());
    }

    @Test
    void rollingBackToCorruptedArtifactFailsVerification() {
        String pipelineId = "corrupt-artifact-pipeline";
        orchestrator.registerPipeline(pipelineId, "corrupt",
                List.of("CHECKOUT", "BUILD", "TEST", "PACKAGE", "RELEASE"));

        var r1 = new com.github.h3yow.buildreleaseorchestratorjava.web.TriggerRequest();
        r1.setIdempotencyKey("idem-ca-1");
        r1.setStageDelayMillis(1L);
        orchestrator.trigger(pipelineId, r1);
        var r2 = new com.github.h3yow.buildreleaseorchestratorjava.web.TriggerRequest();
        r2.setIdempotencyKey("idem-ca-2");
        r2.setSources("changed");
        r2.setStageDelayMillis(1L);
        orchestrator.trigger(pipelineId, r2);

        // Tamper with the stored bytes of 1.0.1 (recorded checksum stays the same),
        // then rolling back to it must fail verification and keep the current pointer.
        boolean tampered = artifactStore.debugTamper(pipelineId, "1.0.1");
        log.info("INPUT tamper artifact version=1.0.1 tampered={}", tampered);
        org.junit.jupiter.api.Assertions.assertTrue(tampered);

        OrchestratorException corrupted = assertThrows(OrchestratorException.class,
                () -> orchestrator.rollback(pipelineId, "1.0.1"));
        log.info("DECISION rollback-to-corrupted rejected errorCode={} message={}",
                corrupted.getErrorCode(), corrupted.getMessage());
        assertEquals(ErrorCode.ARTIFACT_CHECKSUM_MISMATCH, corrupted.getErrorCode());
        assertEquals("1.0.2", orchestrator.getRelease(pipelineId).getCurrentVersion(),
                "a failed rollback must leave the current pointer intact");
    }

    @Test
    void illegalVersionShapeIsRejected() {
        String pipelineId = "bad-version-pipeline";
        orchestrator.registerPipeline(pipelineId, "badver",
                List.of("CHECKOUT", "BUILD", "TEST", "PACKAGE", "RELEASE"));
        var request = new com.github.h3yow.buildreleaseorchestratorjava.web.TriggerRequest();
        request.setIdempotencyKey("idem-bad-version-1");
        request.setRequestedVersion("2.5.0");
        request.setStageDelayMillis(1L);
        OrchestratorException ex = assertThrows(OrchestratorException.class,
                () -> orchestrator.trigger(pipelineId, request));
        log.info("DECISION illegal version rejected errorCode={}", ex.getErrorCode());
        assertEquals(ErrorCode.VERSION_CONFLICT, ex.getErrorCode());
    }
}
