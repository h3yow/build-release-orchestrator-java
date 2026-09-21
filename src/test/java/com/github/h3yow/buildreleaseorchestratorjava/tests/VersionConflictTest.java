package com.github.h3yow.buildreleaseorchestratorjava.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.domain.Artifact;
import com.github.h3yow.buildreleaseorchestratorjava.exception.VersionConflictException;
import com.github.h3yow.buildreleaseorchestratorjava.service.ArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本管理：版本唯一且单调推进；同名冲突拒绝并区分“已存在”与“校验和不一致”。
 */
class VersionConflictTest {

    private ArtifactService artifactService;

    @BeforeEach
    void setUp() {
        artifactService = new ArtifactService(new OrchestratorProperties());
    }

    @Test
    void sameVersionSameContentRejectedAsAlreadyExists() {
        String pipelineId = "p-ver";
        Artifact first = artifactService.register(pipelineId, "1.0.1", "content-A", "test://manual");
        System.out.printf("[test] 输入: 重复注册 version=1.0.1, 相同内容, 首次checksum=%s%n", first.getChecksum());

        VersionConflictException ex = assertThrows(VersionConflictException.class,
                () -> artifactService.register(pipelineId, "1.0.1", "content-A", "test://manual"));
        System.out.printf("[test] 判定依据: code=%s, message=%s%n", ex.getCode(), ex.getMessage());
        assertEquals(VersionConflictException.VERSION_ALREADY_EXISTS, ex.getCode());
        assertEquals(1, artifactService.list(pipelineId).size(), "冲突拒绝后不得产生重复制品");
    }

    @Test
    void sameVersionDifferentContentRejectedAsChecksumMismatch() {
        String pipelineId = "p-ver2";
        artifactService.register(pipelineId, "1.0.1", "content-A", "test://manual");
        System.out.println("[test] 输入: 重复注册 version=1.0.1, 不同内容(content-B)");

        VersionConflictException ex = assertThrows(VersionConflictException.class,
                () -> artifactService.register(pipelineId, "1.0.1", "content-B", "test://manual"));
        System.out.printf("[test] 判定依据: code=%s, message=%s%n", ex.getCode(), ex.getMessage());
        assertEquals(VersionConflictException.CHECKSUM_MISMATCH, ex.getCode());
        // 原制品不被覆盖
        assertEquals("content-A", artifactService.get(pipelineId, "1.0.1").getContent());
    }

    @Test
    void versionsAreMonotonicUnderConcurrency() throws Exception {
        String pipelineId = "p-ver-concurrent";
        int threads = 10;
        System.out.printf("[test] 输入: 并发线程数=%d 各自 registerNext%n", threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Artifact>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return artifactService.registerNext(pipelineId, "content", "test://concurrent");
            }));
        }
        start.countDown();
        List<String> versions = new ArrayList<>();
        for (Future<Artifact> f : futures) {
            versions.add(f.get().getVersion());
        }
        pool.shutdown();

        List<String> sorted = versions.stream().sorted().toList();
        System.out.printf("[test] 判定依据: versions=%s%n", sorted);
        assertEquals(threads, versions.stream().distinct().count(), "版本必须唯一，不得跳变或重复");
        for (int i = 1; i <= threads; i++) {
            assertTrue(versions.contains("1.0." + i), "缺少版本 1.0." + i);
        }
    }

    @Test
    void checksumIsTraceable() {
        String pipelineId = "p-ver-trace";
        Artifact a = artifactService.register(pipelineId, "1.0.1", "content-X", "test://source-A");
        System.out.printf("[test] 判定依据: version=%s, checksum=%s, source=%s%n",
                a.getVersion(), a.getChecksum(), a.getSource());
        assertEquals(ArtifactService.sha256("content-X"), a.getChecksum(), "校验和必须可由内容复算");
        assertEquals("test://source-A", a.getSource(), "制品来源必须可追溯");
        assertTrue(artifactService.verifyChecksum(pipelineId, "1.0.1"));
    }
}
