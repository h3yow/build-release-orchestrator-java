package com.github.h3yow.buildreleaseorchestratorjava.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.domain.PipelineRun;
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
 * 指定版本与普通自动版本混用：
 * 指定版本构建成功后普通触发仍能拿到未占用版本；指定版本被拒绝（两种原因可区分）后
 * 不污染后续自动版本号；连续/并发混用时版本号全局唯一且可继续推进，无半更新状态。
 */
class MixedVersionTriggerTest {

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

    /** 核心回归：指定自动号段版本 1.0.1 成功后，普通触发不得永久撞号，并可持续推进。 */
    @Test
    void normalTriggerWorksAfterSuccessfulSpecifiedAutoRangeVersion() {
        String p = "p-mix-success";
        PipelineService.TriggerResult specified =
                pipelineService.trigger(p, "k-spec", null, "git://repo#main", "content-A", "1.0.1");
        assertEquals("1.0.1", specified.getRun().getProducedVersion());
        System.out.println("[test] 输入: 指定 1.0.1 成功后连续两次普通触发");

        PipelineService.TriggerResult normal1 = pipelineService.trigger(p, "k-n1", null, "git://repo#main", "content-B");
        PipelineService.TriggerResult normal2 = pipelineService.trigger(p, "k-n2", null, "git://repo#main", "content-C");

        System.out.printf("[test] 判定依据: versions=[%s, %s, %s], next=%s, release=%s%n",
                specified.getRun().getProducedVersion(),
                normal1.getRun().getProducedVersion(),
                normal2.getRun().getProducedVersion(),
                artifactService.nextVersion(p), releaseService.currentVersion(p));
        assertEquals("1.0.2", normal1.getRun().getProducedVersion(), "普通触发必须跳过已占用的 1.0.1");
        assertEquals("1.0.3", normal2.getRun().getProducedVersion(), "版本号必须继续单调推进");
        assertEquals(RunStatus.SUCCESS, normal1.getRun().getStatus());
        assertEquals(RunStatus.SUCCESS, normal2.getRun().getStatus());
        assertEquals(3, artifactService.list(p).size(), "三次成功触发应恰好留下三个制品");
        assertEquals("1.0.3", releaseService.currentVersion(p));
        assertEquals("1.0.4", artifactService.nextVersion(p));
    }

    /** 指定自动号段版本成功 + 普通触发后再指定更高自动号段版本，再普通触发仍不撞号、保持单调。 */
    @Test
    void normalTriggerSurvivesAlternatingSpecifiedAndNormalTriggers() {
        String p = "p-mix-alt";
        pipelineService.trigger(p, "k1", null, "r", "c1", "1.0.3");
        pipelineService.trigger(p, "k2", null, "r", "c2");
        pipelineService.trigger(p, "k3", null, "r", "c3", "1.0.6");
        System.out.println("[test] 输入: 指定1.0.3->普通->指定1.0.6，再普通触发");

        PipelineRun last = pipelineService.trigger(p, "k4", null, "r", "c4").getRun();
        System.out.printf("[test] 判定依据: lastVersion=%s, next=%s%n",
                last.getProducedVersion(), artifactService.nextVersion(p));

        // 高水位单调推进：指定1.0.3后普通拿到1.0.4；指定1.0.6后普通拿到1.0.7，永不复用
        assertEquals("1.0.7", last.getProducedVersion(), "交错使用后普通触发仍必须拿到未占用版本且单调推进");
    }

    /** 指定版本被占用（内容一致）被拒绝后，普通触发拿到未占用版本且拒绝无任何副作用。 */
    @Test
    void normalTriggerAdvancesAfterSameContentConflictRejection() {
        String p = "p-mix-reject-same";
        pipelineService.trigger(p, "k1", null, "git://repo#main", "content-A", "1.0.1");
        int artifactsBefore = artifactService.list(p).size();
        VersionConflictException ex = assertThrows(VersionConflictException.class,
                () -> pipelineService.trigger(p, "k2", null, "git://repo#main", "content-A", "1.0.1"));
        assertEquals(VersionConflictException.VERSION_ALREADY_EXISTS, ex.getCode());
        System.out.printf("[test] 输入: 1.0.1 同名同内容被拒绝(code=%s)后普通触发%n", ex.getCode());
        assertEquals("1.0.1", releaseService.currentVersion(p), "拒绝不得移动发布指针");
        assertEquals(artifactsBefore, artifactService.list(p).size(), "拒绝不得新增或覆盖制品");

        PipelineRun normal = pipelineService.trigger(p, "k3", null, "git://repo#main", "content-B").getRun();

        System.out.printf("[test] 判定依据: normalVersion=%s, release=%s, 制品数=%d(拒绝前=%d)%n",
                normal.getProducedVersion(), releaseService.currentVersion(p),
                artifactService.list(p).size(), artifactsBefore);
        assertEquals("1.0.2", normal.getProducedVersion(), "被拒绝不得推进版本，普通触发应为 1.0.2");
        assertEquals("1.0.2", releaseService.currentVersion(p), "普通触发成功后发布指针正常推进");
        assertEquals(artifactsBefore + 1, artifactService.list(p).size(), "拒绝不新增制品，仅普通触发新增一个");
    }

    /** 指定版本被占用（内容不一致）被拒绝后，普通触发正常推进，原制品与指针均不变。 */
    @Test
    void normalTriggerAdvancesAfterChecksumMismatchRejection() {
        String p = "p-mix-reject-diff";
        pipelineService.trigger(p, "k1", null, "git://repo#main", "content-A", "1.0.1");
        String originalContent = artifactService.get(p, "1.0.1").getContent();
        VersionConflictException ex = assertThrows(VersionConflictException.class,
                () -> pipelineService.trigger(p, "k2", null, "git://repo#main", "content-B", "1.0.1"));
        assertEquals(VersionConflictException.CHECKSUM_MISMATCH, ex.getCode());
        assertEquals("1.0.1", releaseService.currentVersion(p), "拒绝不得移动发布指针");
        System.out.printf("[test] 输入: 1.0.1 同名不同内容被拒绝(code=%s)后普通触发%n", ex.getCode());

        PipelineRun normal = pipelineService.trigger(p, "k3", null, "git://repo#main", "content-C").getRun();

        System.out.printf("[test] 判定依据: normalVersion=%s, 原制品内容未变=%s, release=%s%n",
                normal.getProducedVersion(),
                artifactService.get(p, "1.0.1").getContent().equals(originalContent),
                releaseService.currentVersion(p));
        assertEquals("1.0.2", normal.getProducedVersion());
        assertEquals(originalContent, artifactService.get(p, "1.0.1").getContent(), "已有制品不得被覆盖");
        assertEquals("1.0.2", releaseService.currentVersion(p), "普通触发成功后发布指针正常推进");
    }

    /** 连续触发（全部不同幂等键）混合指定与普通：版本集合唯一、无重复、无空洞冲突。 */
    @Test
    void sequentialMixedTriggersProduceUniqueVersions() {
        String p = "p-mix-seq";
        String[][] plan = {
                {"k1", "c1", "1.0.2"},
                {"k2", "c2", null},
                {"k3", "c3", "1.0.4"},
                {"k4", "c4", null},
                {"k5", "c5", null},
        };
        List<String> produced = new ArrayList<>();
        for (String[] step : plan) {
            PipelineRun run = pipelineService.trigger(p, step[0], null, "ref", step[1], step[2]).getRun();
            produced.add(run.getProducedVersion());
        }
        System.out.printf("[test] 判定依据: 连续混合触发 versions=%s%n", produced);
        assertEquals(produced.size(), produced.stream().distinct().count(), "版本号不得重复");
        assertEquals(List.of("1.0.2", "1.0.3", "1.0.4", "1.0.5", "1.0.6"), produced,
                "自动版本沿高水位单调推进，永不与指定版本撞号、永不复用");
    }

    /**
     * 混合并发：先占用若干指定版本（含冲突被拒绝的），再让多个普通触发并发执行，
     * 版本号必须全局唯一、所有触发成功、流水线可继续推进。
     */
    @Test
    void concurrentNormalTriggersAfterSpecifiedVersionsStayUnique() throws Exception {
        String p = "p-mix-concurrent";
        // 指定 1.0.1 与 1.0.5 成功
        pipelineService.trigger(p, "s1", null, "ref", "sc1", "1.0.1");
        pipelineService.trigger(p, "s5", null, "ref", "sc5", "1.0.5");
        // 两次冲突被拒绝（内容一致 / 内容不一致），不得污染版本号
        assertThrows(VersionConflictException.class,
                () -> pipelineService.trigger(p, "s1-dup", null, "ref", "sc1", "1.0.1"));
        assertThrows(VersionConflictException.class,
                () -> pipelineService.trigger(p, "s5-other", null, "ref", "scX", "1.0.5"));
        int threads = 6;
        System.out.printf("[test] 输入: 指定1.0.1/1.0.5且两次冲突被拒后，%d 个普通触发并发%n", threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PipelineService.TriggerResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return pipelineService.trigger(p, "n-" + idx, null, "ref", "nc-" + idx);
            }));
        }
        ready.await();
        start.countDown();
        List<String> versions = new ArrayList<>();
        for (Future<PipelineService.TriggerResult> f : futures) {
            PipelineRun run = f.get().getRun();
            assertEquals(RunStatus.SUCCESS, run.getStatus(), "并发普通触发都必须成功");
            versions.add(run.getProducedVersion());
        }
        pool.shutdown();

        List<String> allVersions = new ArrayList<>(versions);
        allVersions.add("1.0.1");
        allVersions.add("1.0.5");
        System.out.printf("[test] 判定依据: 并发versions=%s, 全部(含指定)=%s, next=%s%n",
                versions.stream().sorted().toList(),
                allVersions.stream().sorted().toList(), artifactService.nextVersion(p));
        assertEquals(threads, versions.stream().distinct().count(), "并发分配的版本必须互不相同");
        assertEquals(allVersions.size(), allVersions.stream().distinct().count(),
                "并发版本不得与已有指定版本撞号");
        // 被占用的 1.0.1/1.0.5 不得被分配给普通触发
        assertTrue(!versions.contains("1.0.1") && !versions.contains("1.0.5"),
                "已被指定版本占用的号不得再分配");
        // 高水位单调：被拒不消耗版本，水位停在 5，6 个并发依次拿到 1.0.6~1.0.11（完成顺序不限）
        java.util.Set<String> expected = IntStream.rangeClosed(6, 11).mapToObj(n -> "1.0." + n)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(expected, new java.util.HashSet<>(versions),
                "被拒绝不消耗版本；自动版本沿高水位继续单调推进");
        assertEquals(threads + 2, artifactService.list(p).size(), "制品数 = 2 个指定 + 6 个并发");
        assertEquals("1.0.11", releaseService.currentVersion(p), "发布指针指向并发结束后最高版本");
        assertEquals("1.0.12", artifactService.nextVersion(p), "水位推进后仍可继续分配新版本");
    }
}
