package com.github.h3yow.buildreleaseorchestratorjava.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.github.h3yow.buildreleaseorchestratorjava.config.OrchestratorProperties;
import com.github.h3yow.buildreleaseorchestratorjava.domain.Artifact;
import com.github.h3yow.buildreleaseorchestratorjava.domain.BuildInput;
import com.github.h3yow.buildreleaseorchestratorjava.domain.CacheLookupResult;
import com.github.h3yow.buildreleaseorchestratorjava.domain.PipelineRun;
import com.github.h3yow.buildreleaseorchestratorjava.domain.RunStatus;
import com.github.h3yow.buildreleaseorchestratorjava.domain.StageResult;
import com.github.h3yow.buildreleaseorchestratorjava.domain.StageStatus;
import com.github.h3yow.buildreleaseorchestratorjava.exception.LimitExceededException;
import com.github.h3yow.buildreleaseorchestratorjava.exception.OrchestratorException;
import com.github.h3yow.buildreleaseorchestratorjava.exception.RunNotFoundException;
import com.github.h3yow.buildreleaseorchestratorjava.exception.VersionConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 流水线编排：阶段按序执行、失败即停、断点重试、幂等触发、并发一致。
 * 阶段顺序：CHECKOUT -> BUILD(含缓存判定) -> TEST -> PACKAGE -> PUBLISH。
 * 每次触发携带本次构建身份（源码标识+内容）与可选的期望版本。
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    static final List<String> STAGE_ORDER = List.of("CHECKOUT", "BUILD", "TEST", "PACKAGE", "PUBLISH");

    private final IdempotencyService idempotencyService;
    private final ArtifactService artifactService;
    private final BuildCacheService buildCacheService;
    private final ReleaseService releaseService;
    private final OrchestratorProperties properties;

    private final Map<String, PipelineRun> runs = new ConcurrentHashMap<>();
    /** runId -> 一次性故障注入点（注入后即消费，重试不再生效） */
    private final Map<String, String> failAtStageByRunId = new ConcurrentHashMap<>();
    /** runId -> 本次触发携带的构建输入（重试时复用同一构建身份） */
    private final Map<String, BuildInput> inputsByRunId = new ConcurrentHashMap<>();
    /** runId -> BUILD 阶段产出的构建产物内容（PUBLISH 据此打包，保证命中复用时逐字节一致） */
    private final Map<String, String> compiledPayloadsByRunId = new ConcurrentHashMap<>();
    private final Map<String, Object> pipelineLocks = new ConcurrentHashMap<>();

    public PipelineService(IdempotencyService idempotencyService, ArtifactService artifactService,
                           BuildCacheService buildCacheService, ReleaseService releaseService,
                           OrchestratorProperties properties) {
        this.idempotencyService = idempotencyService;
        this.artifactService = artifactService;
        this.buildCacheService = buildCacheService;
        this.releaseService = releaseService;
        this.properties = properties;
    }

    public static class TriggerResult {
        private final PipelineRun run;
        private final boolean duplicated;

        public TriggerResult(PipelineRun run, boolean duplicated) {
            this.run = run;
            this.duplicated = duplicated;
        }

        public PipelineRun getRun() { return run; }
        public boolean isDuplicated() { return duplicated; }
    }

    /**
     * 触发流水线（兼容旧入口）：使用按流水线派生的默认构建输入，不指定期望版本。
     */
    public TriggerResult trigger(String pipelineId, String idempotencyKey, String failAtStage) {
        return trigger(pipelineId, idempotencyKey, failAtStage, defaultBuildInput(pipelineId), null);
    }

    /**
     * 触发流水线：同一幂等键重复提交返回首次运行且标记 duplicated。
     *
     * @param failAtStage     测试用一次性故障注入点，为 null 表示不注入
     * @param buildInput      本次构建实际输入（源码标识与内容），为 null 时使用默认输入
     * @param expectedVersion 期望产出的版本（1.0.N），为 null 表示自动分配下一个版本
     */
    public TriggerResult trigger(String pipelineId, String idempotencyKey, String failAtStage,
                                 BuildInput buildInput, String expectedVersion) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new OrchestratorException("IDEMPOTENCY_KEY_REQUIRED", "幂等键不能为空: pipeline=" + pipelineId);
        }
        BuildInput input = buildInput != null ? buildInput : defaultBuildInput(pipelineId);
        validateBuildInput(pipelineId, input);
        String wantVersion = normalizeExpectedVersion(expectedVersion);
        if (wantVersion != null) {
            // 版本格式在产生任何运行/副作用前校验，避免留下失败运行等半更新状态
            ArtifactService.validateVersionSequence(wantVersion);
        }
        log.info("[pipeline] 触发请求: pipeline={}, idempotencyKey={}, sourceRef={}, fingerprint={}, expectedVersion={}, failAtStage={}",
                pipelineId, idempotencyKey, input.getSourceRef(),
                ArtifactService.sha256(input.getSourceContent()), wantVersion, failAtStage);
        IdempotencyService.IdempotentResult<PipelineRun> result = idempotencyService.execute(
                "trigger:" + pipelineId, idempotencyKey,
                () -> startNewRun(pipelineId, idempotencyKey, failAtStage, input, wantVersion));
        if (result.isDuplicated()) {
            log.info("[pipeline] 重复触发: pipeline={}, idempotencyKey={}, 返回首次运行 runId={}",
                    pipelineId, idempotencyKey, result.getValue().getRunId());
        }
        return new TriggerResult(result.getValue(), result.isDuplicated());
    }

    private BuildInput defaultBuildInput(String pipelineId) {
        return new BuildInput("default", "source-of-" + pipelineId);
    }

    private void validateBuildInput(String pipelineId, BuildInput input) {
        if (input.getSourceRef() == null || input.getSourceRef().isBlank()) {
            throw new OrchestratorException("BUILD_INPUT_REQUIRED",
                    "构建输入源码标识(sourceRef)不能为空: pipeline=" + pipelineId);
        }
        if (input.getSourceContent() == null) {
            throw new OrchestratorException("BUILD_INPUT_REQUIRED",
                    "构建输入源码内容(sourceContent)不能为空: pipeline=" + pipelineId + ", ref=" + input.getSourceRef());
        }
    }

    private String normalizeExpectedVersion(String expectedVersion) {
        if (expectedVersion == null || expectedVersion.isBlank()) {
            return null;
        }
        return expectedVersion.trim();
    }

    private PipelineRun startNewRun(String pipelineId, String idempotencyKey, String failAtStage,
                                    BuildInput input, String expectedVersion) {
        String runId = UUID.randomUUID().toString();
        List<StageResult> stages = new ArrayList<>();
        for (String name : STAGE_ORDER) {
            stages.add(new StageResult(name));
        }
        PipelineRun run = new PipelineRun(runId, pipelineId, idempotencyKey, stages);
        run.setSourceRef(input.getSourceRef());
        run.setSourceFingerprint(ArtifactService.sha256(input.getSourceContent()));
        run.setExpectedVersion(expectedVersion);
        if (failAtStage != null && !failAtStage.isBlank()) {
            failAtStageByRunId.put(runId, failAtStage);
        }
        storeRun(run);
        execute(run, input);
        return run;
    }

    /** 运行记录数上限：超限淘汰最早已完成运行；无可淘汰则拒绝，不留部分状态。 */
    private void storeRun(PipelineRun run) {
        int max = properties.getMaxRuns();
        while (runs.size() >= max) {
            PipelineRun oldestFinished = runs.values().stream()
                    .filter(r -> r.getStatus() != RunStatus.RUNNING)
                    .min(Comparator.comparing(PipelineRun::getCreatedAt))
                    .orElse(null);
            if (oldestFinished == null) {
                log.warn("[pipeline] 运行数量超限且无可淘汰项: max={}, 拒绝触发", max);
                throw new LimitExceededException("运行数量超限且无可淘汰项: maxRuns=" + max);
            }
            runs.remove(oldestFinished.getRunId());
            inputsByRunId.remove(oldestFinished.getRunId());
            compiledPayloadsByRunId.remove(oldestFinished.getRunId());
            failAtStageByRunId.remove(oldestFinished.getRunId());
            log.info("[pipeline] 淘汰最早运行记录: runId={}, maxRuns={}", oldestFinished.getRunId(), max);
        }
        runs.put(run.getRunId(), run);
    }

    /**
     * 从失败点重试：已成功阶段不得重复执行或重复计时。
     */
    public PipelineRun retry(String runId) {
        PipelineRun run = getRun(runId);
        synchronized (run) {
            if (run.getStatus() != RunStatus.FAILED) {
                throw new OrchestratorException("RUN_NOT_FAILED",
                        "仅失败的运行可重试: runId=" + runId + ", status=" + run.getStatus());
            }
            log.info("[pipeline] 重试运行: runId={}, pipeline={}, 从失败点继续", runId, run.getPipelineId());
            run.setStatus(RunStatus.RUNNING);
            run.setFailureReason(null);
            BuildInput input = buildInputOf(run);
            execute(run, input);
            return run;
        }
    }

    /** 重试时按运行记录的构建身份重建输入；运行被淘汰等极端情况下退化为默认输入。 */
    private BuildInput buildInputOf(PipelineRun run) {
        BuildInput byRef = inputsByRunId.get(run.getRunId());
        return byRef != null ? byRef : defaultBuildInput(run.getPipelineId());
    }

    /** 按序执行阶段：跳过已成功阶段，失败即停。每条流水线串行执行，保证版本推进一致。 */
    private void execute(PipelineRun run, BuildInput input) {
        inputsByRunId.put(run.getRunId(), input);
        Object pipelineLock = pipelineLocks.computeIfAbsent(run.getPipelineId(), k -> new Object());
        synchronized (pipelineLock) {
            for (StageResult stage : run.getStages()) {
                if (stage.getStatus() == StageStatus.SUCCESS) {
                    log.info("[pipeline] 跳过已成功阶段: runId={}, stage={}, 原耗时={}ms",
                            run.getRunId(), stage.getName(), stage.getDurationMillis());
                    continue;
                }
                if (!runStage(run, stage, input)) {
                    run.setStatus(RunStatus.FAILED);
                    run.setFailureReason("阶段失败: " + stage.getName() + ", 详情: " + stage.getDetail());
                    log.warn("[pipeline] 运行失败: runId={}, stage={}, reason={}",
                            run.getRunId(), stage.getName(), stage.getDetail());
                    return;
                }
            }
            run.setStatus(RunStatus.SUCCESS);
            log.info("[pipeline] 运行成功: runId={}, pipeline={}, version={}",
                    run.getRunId(), run.getPipelineId(), run.getProducedVersion());
        }
    }

    /** 执行单个阶段，返回是否成功。计时只在本方法内发生，成功阶段不会被再次进入。 */
    private boolean runStage(PipelineRun run, StageResult stage, BuildInput input) {
        stage.setStatus(StageStatus.RUNNING);
        stage.setStartedAt(Instant.now());
        long start = System.nanoTime();
        String pipelineId = run.getPipelineId();
        try {
            // 一次性故障注入：在阶段产生任何副作用前注入，注入后即消费，重试不再生效
            String failAt = failAtStageByRunId.get(run.getRunId());
            if (stage.getName().equals(failAt)) {
                failAtStageByRunId.remove(run.getRunId());
                throw new OrchestratorException("INJECTED_FAILURE",
                        "注入故障: stage=" + failAt + " (一次性，重试不再生效)");
            }
            String detail = switch (stage.getName()) {
                case "CHECKOUT" -> doCheckout(run, input);
                case "BUILD" -> doBuild(run, input);
                case "TEST" -> "测试通过";
                case "PACKAGE" -> "打包完成";
                case "PUBLISH" -> doPublish(run, input);
                default -> throw new OrchestratorException("UNKNOWN_STAGE", "未知阶段: " + stage.getName());
            };
            stage.setDetail(detail);
            stage.setStatus(StageStatus.SUCCESS);
            return true;
        } catch (VersionConflictException e) {
            // 版本冲突是对触发请求的确定性拒绝：标记运行失败、不留半更新状态，同时把冲突原因传播给调用方(HTTP 409)
            stage.setDetail(e.getMessage());
            stage.setStatus(StageStatus.FAILED);
            run.setStatus(RunStatus.FAILED);
            run.setFailureReason("阶段失败: " + stage.getName() + ", 详情: " + e.getMessage());
            log.warn("[pipeline] 运行被拒绝: runId={}, stage={}, code={}, reason={}",
                    run.getRunId(), stage.getName(), e.getCode(), e.getMessage());
            throw e;
        } catch (Exception e) {
            stage.setDetail(e.getMessage());
            stage.setStatus(StageStatus.FAILED);
            return false;
        } finally {
            stage.setFinishedAt(Instant.now());
            stage.setDurationMillis((System.nanoTime() - start) / 1_000_000L);
            log.info("[pipeline] 阶段结束: runId={}, pipeline={}, stage={}, status={}, duration={}ms",
                    run.getRunId(), pipelineId, stage.getName(), stage.getStatus(), stage.getDurationMillis());
        }
    }

    private String doCheckout(PipelineRun run, BuildInput input) {
        String fingerprint = ArtifactService.sha256(input.getSourceContent());
        return "拉取源码完成: ref=" + input.getSourceRef() + ", fingerprint=" + fingerprint;
    }

    private String doBuild(PipelineRun run, BuildInput input) {
        String pipelineId = run.getPipelineId();
        String fingerprint = ArtifactService.sha256(input.getSourceContent());
        String cacheKey = cacheKeyOf(pipelineId, input.getSourceRef());
        CacheLookupResult lookup = buildCacheService.lookup(cacheKey, fingerprint);
        run.setCacheHit(lookup.isHit());
        run.setCacheReason(lookup.getReason().name());
        if (lookup.isHit()) {
            // 复用缓存中的构建产物：最终制品内容与首次构建逐字节一致，校验和一致，不重新构建
            compiledPayloadsByRunId.put(run.getRunId(), lookup.getPayload());
            run.setReusedBuild(true);
            return "缓存命中: checksum=" + lookup.getChecksum() + "，复用既有构建结果";
        }
        String payload = "compiled:" + input.getSourceContent();
        buildCacheService.put(cacheKey, fingerprint, payload);
        compiledPayloadsByRunId.put(run.getRunId(), payload);
        run.setReusedBuild(false);
        return "缓存未命中(" + lookup.getReason() + ")，已重新构建并写回缓存";
    }

    private String doPublish(PipelineRun run, BuildInput input) {
        String pipelineId = run.getPipelineId();
        String payload = compiledPayloadsByRunId.get(run.getRunId());
        if (payload == null) {
            // 重试等场景下若产物上下文缺失，按确定性规则重建（与首次构建内容一致）
            payload = "compiled:" + input.getSourceContent();
        }
        String content = "artifact:" + payload;
        String source = "pipeline://" + pipelineId + "/run/" + run.getRunId();
        Artifact artifact;
        if (run.getExpectedVersion() != null) {
            artifact = artifactService.registerExpected(pipelineId, run.getExpectedVersion(), content, source);
        } else {
            artifact = artifactService.registerNext(pipelineId, content, source);
        }
        releaseService.release(pipelineId, artifact.getVersion());
        run.setProducedVersion(artifact.getVersion());
        return "发布版本 " + artifact.getVersion() + ", checksum=" + artifact.getChecksum();
    }

    /** 缓存键按流水线与源码标识划分命名空间：同一 ref 内容变化表现为不兼容，不同 ref 表现为无条目。 */
    private String cacheKeyOf(String pipelineId, String sourceRef) {
        return pipelineId + "::build::" + sourceRef;
    }

    public PipelineRun getRun(String runId) {
        PipelineRun run = runs.get(runId);
        if (run == null) {
            throw new RunNotFoundException("运行不存在: runId=" + runId);
        }
        return run;
    }

    public List<PipelineRun> listRuns() {
        List<PipelineRun> list = new ArrayList<>(runs.values());
        list.sort(Comparator.comparing(PipelineRun::getCreatedAt));
        return list;
    }
}
