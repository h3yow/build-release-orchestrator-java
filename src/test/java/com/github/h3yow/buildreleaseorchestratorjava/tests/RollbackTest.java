package com.github.h3yow.buildreleaseorchestratorjava.tests;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.domain.Artifact;
import com.github.h3yow.buildreleaseorchestratorjava.exception.RollbackRejectedException;
import com.github.h3yow.buildreleaseorchestratorjava.service.ArtifactService;
import com.github.h3yow.buildreleaseorchestratorjava.service.BuildCacheService;
import com.github.h3yow.buildreleaseorchestratorjava.service.IdempotencyService;
import com.github.h3yow.buildreleaseorchestratorjava.service.PipelineService;
import com.github.h3yow.buildreleaseorchestratorjava.service.ReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回滚：幂等、校验和与版本指向一致；回滚到不存在或不可用制品必须被拒绝。
 */
class RollbackTest {

    private ArtifactService artifactService;
    private ReleaseService releaseService;
    private PipelineService pipelineService;

    @BeforeEach
    void setUp() {
        OrchestratorProperties props = new OrchestratorProperties();
        artifactService = new ArtifactService(props);
        releaseService = new ReleaseService(artifactService);
        releaseService.wireCurrentVersionProvider();
        pipelineService = new PipelineService(new IdempotencyService(), artifactService,
                new BuildCacheService(props), releaseService, props);
    }

    @Test
    void rollbackToExistingArtifactIsIdempotentAndConsistent() {
        String pipelineId = "p-rollback";
        pipelineService.trigger(pipelineId, "k1", null);
        pipelineService.trigger(pipelineId, "k2", null);
        assertEquals("1.0.2", releaseService.currentVersion(pipelineId));
        System.out.printf("[test] 输入: pipeline=%s, 当前版本=1.0.2, 回滚目标=1.0.1, 连续回滚两次%n", pipelineId);

        ReleaseService.ReleaseState first = releaseService.rollback(pipelineId, "1.0.1");
        ReleaseService.ReleaseState second = releaseService.rollback(pipelineId, "1.0.1");

        Artifact target = artifactService.get(pipelineId, "1.0.1");
        System.out.printf("[test] 判定依据: first.changed=%s, second.changed=%s, 当前版本=%s, 目标checksum=%s, 校验通过=%s%n",
                first.isChanged(), second.isChanged(), releaseService.currentVersion(pipelineId),
                target.getChecksum(), artifactService.verifyChecksum(pipelineId, "1.0.1"));

        assertTrue(first.isChanged(), "首次回滚应产生变更");
        assertFalse(second.isChanged(), "重复回滚必须幂等(无变更)");
        assertEquals("1.0.1", releaseService.currentVersion(pipelineId));
        assertTrue(artifactService.verifyChecksum(pipelineId, "1.0.1"), "回滚后校验和必须一致");
    }

    @Test
    void rollbackToNonexistentArtifactIsRejected() {
        String pipelineId = "p-rollback-missing";
        pipelineService.trigger(pipelineId, "k1", null);
        System.out.printf("[test] 输入: pipeline=%s, 回滚目标=9.9.9(不存在)%n", pipelineId);

        RollbackRejectedException ex = assertThrows(RollbackRejectedException.class,
                () -> releaseService.rollback(pipelineId, "9.9.9"));
        System.out.printf("[test] 判定依据: code=%s, message=%s, 当前版本=%s%n",
                ex.getCode(), ex.getMessage(), releaseService.currentVersion(pipelineId));
        assertEquals("ARTIFACT_NOT_FOUND", ex.getCode());
        assertEquals("1.0.1", releaseService.currentVersion(pipelineId), "拒绝后版本指向不得变化");
    }

    @Test
    void rollbackToCorruptedArtifactIsRejected() {
        String pipelineId = "p-rollback-corrupt";
        pipelineService.trigger(pipelineId, "k1", null);
        pipelineService.trigger(pipelineId, "k2", null);
        artifactService.simulateCorruption(pipelineId, "1.0.1");
        System.out.printf("[test] 输入: pipeline=%s, 目标=1.0.1(已损坏), 当前=1.0.2%n", pipelineId);

        RollbackRejectedException ex = assertThrows(RollbackRejectedException.class,
                () -> releaseService.rollback(pipelineId, "1.0.1"));
        System.out.printf("[test] 判定依据: code=%s, message=%s, 当前版本=%s%n",
                ex.getCode(), ex.getMessage(), releaseService.currentVersion(pipelineId));
        assertEquals("CHECKSUM_MISMATCH", ex.getCode());
        assertEquals("1.0.2", releaseService.currentVersion(pipelineId), "拒绝后版本指向不得变化");
    }
}
