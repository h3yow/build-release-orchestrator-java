package com.github.h3yow.buildreleaseorchestratorjava.tests;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.domain.Artifact;
import com.github.h3yow.buildreleaseorchestratorjava.domain.RunStatus;
import com.github.h3yow.buildreleaseorchestratorjava.exception.VersionConflictException;
import com.github.h3yow.buildreleaseorchestratorjava.service.ArtifactService;
import com.github.h3yow.buildreleaseorchestratorjava.service.BuildCacheService;
import com.github.h3yow.buildreleaseorchestratorjava.service.IdempotencyService;
import com.github.h3yow.buildreleaseorchestratorjava.service.PipelineService;
import com.github.h3yow.buildreleaseorchestratorjava.service.ReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指定版本与普通自动版本混用：指定版本成功后普通触发必须继续可用；
 * 冲突被拒绝不得改变后续可用版本号；混合并发下版本号唯一且持续推进。
 */
class MixedVersionAllocationTest {

    private PipelineService pipelineService;
    private ArtifactService artifactService;
    private ReleaseService releaseService;

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
    void autoTriggerWorksAfterSpecifiedVersionInAutoSpace() {
        String p = "p-mix-auto-after-spec";
        System.out.println("[test] 输入: 先指定 requestedVersion=1.0.1 成功，再连续普通触发两次");

        PipelineService.TriggerResult r1 = pipelineService.trigger(p, "k1", null, null, null, "1.0.1");
        PipelineService.TriggerResult r2 = pipelineService.trigger(p, "k2", null);
        PipelineService.TriggerResult r3 = pipelineService.trigger(p, "k3", null);

        System.out.printf("[test] 判定依据: versions=%s,%s,%s, currentRelease=%s%n",
                r1.getRun().getProducedVersion(), r2.getRun().getProducedVersion(),
                r3.getRun().getProducedVersion(), releaseService.currentVersion(p));
        assertEquals("1.0.1", r1.getRun().getProducedVersion());
        assertEquals("1.0.2", r2.getRun().getProducedVersion(),
                "指定版本占用 1.0.1 后，普通触发必须拿到下一个未占用版本而不是冲突失败");
        assertEquals("1.0.3", r3.getRun().getProducedVersion(), "普通触发必须能持续推进");
        assertEquals(RunStatus.SUCCESS, r2.getRun().getStatus());
        assertEquals(RunStatus.SUCCESS, r3.getRun().getStatus());
        assertEquals("1.0.3", releaseService.currentVersion(p));
        assertEquals(3, artifactService.list(p).size());
    }

    @Test
    void autoTriggerSkipsOnlyOccupiedSegmentAfterSpecifiedVersion() {
        String p = "p-mix-skip";
        System.out.println("[test] 输入: 指定 requestedVersion=1.0.3 成功，再普通触发");

        pipelineService.trigger(p, "k1", null, null, null, "1.0.3");
        PipelineService.TriggerResult r = pipelineService.trigger(p, "k2", null);

        System.out.printf("[test] 判定依据: producedVersion=%s, nextVersion=%s%n",
                r.getRun().getProducedVersion(), artifactService.nextVersion(p));
        assertEquals("1.0.4", r.getRun().getProducedVersion(),
                "自动号段被指定版本推进后，普通触发必须从占用点之后继续，不得重复分配");
        assertEquals("1.0.5", artifactService.nextVersion(p));
    }

    @Test
    void autoTriggerUnaffectedByRejectedSpecifiedVersion() {
        String p = "p-mix-reject";
        pipelineService.trigger(p, "k1", null, "git://repo#main", "content-A", "1.0.1");
        String releaseBefore = releaseService.currentVersion(p);
        int artifactsBefore = artifactService.list(p).size();
        System.out.println("[test] 输入: 指定版本 1.0.1 冲突被拒绝后，再普通触发");

        VersionConflictException ex = assertThrows(VersionConflictException.class,
                () -> pipelineService.trigger(p, "k2", null, "git://repo#main", "content-B", "1.0.1"));
        PipelineService.TriggerResult r = pipelineService.trigger(p, "k3", null);

        System.out.printf("[test] 判定依据: 拒绝code=%s, 普通触发出版本=%s, release=%s, 制品数=%d%n",
                ex.getCode(), r.getRun().getProducedVersion(),
                releaseService.currentVersion(p), artifactService.list(p).size());
        assertEquals(VersionConflictException.CHECKSUM_MISMATCH, ex.getCode());
        assertEquals("1.0.2", r.getRun().getProducedVersion(),
                "被拒绝的指定版本不得改变后续普通触发可用的版本号");
        assertEquals("1.0.2", releaseService.currentVersion(p));
        assertEquals(artifactsBefore + 1, artifactService.list(p).size(), "拒绝不得留半更新，成功才新增制品");
        assertTrue(artifactService.list(p).stream().noneMatch(a -> a.getVersion().equals("1.0.1")
                && !a.getContent().equals(artifactService.get(p, "1.0.1").getContent())),
                "已有制品不得被覆盖");
    }

    @Test
    void rejectionReasonsRemainDistinctInMixedFlow() {
        String p = "p-mix-reasons";
        pipelineService.trigger(p, "k1", null, "git://repo#main", "content-A", "1.0.1");
        System.out.println("[test] 输入: 同版本分别携带相同/不同内容再次指定");

        VersionConflictException same = assertThrows(VersionConflictException.class,
                () -> pipelineService.trigger(p, "k2", null, "git://repo#main", "content-A", "1.0.1"));
        VersionConflictException diff = assertThrows(VersionConflictException.class,
                () -> pipelineService.trigger(p, "k3", null, "git://repo#main", "content-B", "1.0.1"));
        PipelineService.TriggerResult auto = pipelineService.trigger(p, "k4", null);

        System.out.printf("[test] 判定依据: same=%s, diff=%s, 普通触发出版本=%s%n",
                same.getCode(), diff.getCode(), auto.getRun().getProducedVersion());
        assertEquals(VersionConflictException.VERSION_ALREADY_EXISTS, same.getCode(),
                "同名且内容一致必须判定为 VERSION_ALREADY_EXISTS");
        assertEquals(VersionConflictException.CHECKSUM_MISMATCH, diff.getCode(),
                "同名但内容不一致必须判定为 CHECKSUM_MISMATCH");
        assertEquals("1.0.2", auto.getRun().getProducedVersion(), "两次拒绝均不得扰乱版本推进");
        assertEquals(2, artifactService.list(p).size(), "只应有 1.0.1 与 1.0.2 两个制品");
    }

    @Test
    void mixedConcurrentTriggersAllocateUniqueVersions() throws Exception {
        String p = "p-mix-concurrent";
        int autoThreads = 6;
        int specThreads = 4;
        int total = autoThreads + specThreads;
        System.out.printf("[test] 输入: 并发 %d 个普通触发 + %d 个指定版本(1.0.10x)触发%n", autoThreads, specThreads);

        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < autoThreads; i++) {
            final String key = "auto-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return pipelineService.trigger(p, key, null).getRun().getProducedVersion();
            }));
        }
        for (int i = 0; i < specThreads; i++) {
            final String key = "spec-" + i;
            final String version = "1.0.10" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return pipelineService.trigger(p, key, null, null, null, version)
                        .getRun().getProducedVersion();
            }));
        }
        start.countDown();
        List<String> versions = new ArrayList<>();
        for (Future<String> f : futures) {
            versions.add(f.get());
        }
        pool.shutdown();

        Set<String> distinct = new HashSet<>(versions);
        System.out.printf("[test] 判定依据: versions=%s, 制品数=%d%n",
                versions.stream().sorted().toList(), artifactService.list(p).size());
        assertEquals(total, distinct.size(), "混合并发下版本号必须唯一，不得重复分配");
        assertEquals(total, artifactService.list(p).size(), "每次成功触发必须恰好落一个制品");
        Set<String> specVersions = new HashSet<>();
        for (int i = 0; i < specThreads; i++) {
            specVersions.add("1.0.10" + i);
            assertTrue(distinct.contains("1.0.10" + i), "指定版本必须按请求产出: 1.0.10" + i);
        }
        long autoCount = distinct.stream().filter(v -> !specVersions.contains(v)).count();
        assertEquals(autoThreads, autoCount, "普通触发必须全部落在自动号段且互不重复");

        // 混合并发之后普通触发仍能继续推进
        PipelineService.TriggerResult after = pipelineService.trigger(p, "after", null);
        System.out.printf("[test] 判定依据: 并发后普通触发出版本=%s%n", after.getRun().getProducedVersion());
        assertEquals(RunStatus.SUCCESS, after.getRun().getStatus());
        assertTrue(!distinct.contains(after.getRun().getProducedVersion()),
                "并发之后的普通触发仍必须拿到未占用版本");
    }

    @Test
    void concurrentDuplicateSpecifiedVersionRejectedWithoutCorruptingProgress() throws Exception {
        String p = "p-mix-dup-concurrent";
        int threads = 5;
        System.out.printf("[test] 输入: 并发 %d 个线程以不同内容指定同一版本 1.0.1，随后普通触发%n", threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String key = "dup-" + i;
            final String content = "content-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return "OK:" + pipelineService.trigger(p, key, null, null, content, "1.0.1")
                            .getRun().getProducedVersion();
                } catch (VersionConflictException e) {
                    return "REJECTED:" + e.getCode();
                }
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : futures) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        long ok = outcomes.stream().filter(o -> o.startsWith("OK")).count();
        long rejected = outcomes.stream().filter(o -> o.startsWith("REJECTED")).count();
        System.out.printf("[test] 判定依据: outcomes=%s%n", outcomes);
        assertEquals(1, ok, "同一指定版本并发只能成功一次");
        assertEquals(threads - 1, rejected, "其余必须被冲突拒绝");
        assertEquals(1, artifactService.list(p).size(), "被拒绝的触发不得留下半更新制品");

        PipelineService.TriggerResult after = pipelineService.trigger(p, "after-dup", null);
        assertEquals("1.0.2", after.getRun().getProducedVersion(),
                "并发拒绝不得扰乱后续普通触发的版本推进");
        assertEquals("1.0.2", releaseService.currentVersion(p));
    }
}
