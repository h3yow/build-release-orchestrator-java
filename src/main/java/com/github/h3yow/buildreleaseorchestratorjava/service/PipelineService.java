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
import com.github.h3yow.buildreleaseorchestratorjava.domain.CacheLookupResult;
import com.github.h3yow.buildreleaseorchestratorjava.domain.PipelineRun;
import com.github.h3yow.buildreleaseorchestratorjava.domain.RunStatus;
import com.github.h3yow.buildreleaseorchestratorjava.domain.StageResult;
import com.github.h3yow.buildreleaseorchestratorjava.domain.StageStatus;
import com.github.h3yow.buildreleaseorchestratorjava.exception.LimitExceededException;
import com.github.h3yow.buildreleaseorchestratorjava.exception.OrchestratorException;
import com.github.h3yow.buildreleaseorchestratorjava.exception.RunNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 流水线编排：阶段按序执行、失败即停、断点重试、幂等触发、并发一致。
 * 阶段顺序：CHECKOUT -> BUILD(含缓存判定) -> TEST -> PACKAGE -> PUBLISH。
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
     * 触发流水线：同一幂等键重复提交返回首次运行且标记 duplicated。
     *
     * @param failAtStage 测试用一次性故障注入点，为 null 表示不注入
     */
    public TriggerResult trigger(String pipelineId, String idempotencyKey, String failAtStage) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new OrchestratorException("IDEMPOTENCY_KEY_REQUIRED", "幂等键不能为空: pipeline=" + pipelineId);
        }
        log.info("[pipeline] 触发请求: pipeline={}, idempotencyKey={}, failAtStage={}",
                pipelineId, idempotencyKey, failAtStage);
        IdempotencyService.IdempotentResult<PipelineRun> result = idempotencyService.execute(
                "trigger:" + pipelineId, idempotencyKey,
                () -> startNewRun(pipelineId, idempotencyKey, failAtStage));
        if (result.isDuplicated()) {
            log.info("[pipeline] 重复触发: pipeline={}, idempotencyKey={}, 返回首次运行 runId={}",
                    pipelineId, idempotencyKey, result.getValue().getRunId());
        }
        return new TriggerResult(result.getValue(), result.isDuplicated());
    }

    private PipelineRun startNewRun(String pipelineId, String idempotencyKey, String failAtStage) {
        String runId = UUID.randomUUID().toString();
        List<StageResult> stages = new ArrayList<>();
        for (String name : STAGE_ORDER) {
            stages.add(new StageResult(name));
        }
        PipelineRun run = new PipelineRun(runId, pipelineId, idempotencyKey, stages);
        if (failAtStage != null && !failAtStage.isBlank()) {
            failAtStageByRunId.put(runId, failAtStage);
        }
        storeRun(run);
        execute(run);
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
            execute(run);
            return run;
        }
    }

    /** 按序执行阶段：跳过已成功阶段，失败即停。每条流水线串行执行，保证版本推进一致。 */
    private void execute(PipelineRun run) {
        Object pipelineLock = pipelineLocks.computeIfAbsent(run.getPipelineId(), k -> new Object());
        synchronized (pipelineLock) {
            for (StageResult stage : run.getStages()) {
                if (stage.getStatus() == StageStatus.SUCCESS) {
                    log.info("[pipeline] 跳过已成功阶段: runId={}, stage={}, 原耗时={}ms",
                            run.getRunId(), stage.getName(), stage.getDurationMillis());
                    continue;
                }
                if (!runStage(run, stage)) {
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
    private boolean runStage(PipelineRun run, StageResult stage) {
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
                case "CHECKOUT" -> doCheckout(run);
                case "BUILD" -> doBuild(run);
                case "TEST" -> "测试通过";
                case "PACKAGE" -> "打包完成";
                case "PUBLISH" -> doPublish(run);
                default -> throw new OrchestratorException("UNKNOWN_STAGE", "未知阶段: " + stage.getName());
            };
            stage.setDetail(detail);
            stage.setStatus(StageStatus.SUCCESS);
            return true;
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

    private String sourceContentOf(String pipelineId) {
        return "source-of-" + pipelineId;
    }

    private String doCheckout(PipelineRun run) {
        return "拉取源码完成: fingerprint=" + ArtifactService.sha256(sourceContentOf(run.getPipelineId()));
    }

    private String doBuild(PipelineRun run) {
        String pipelineId = run.getPipelineId();
        String fingerprint = ArtifactService.sha256(sourceContentOf(pipelineId));
        String cacheKey = pipelineId + "::build";
        CacheLookupResult lookup = buildCacheService.lookup(cacheKey, fingerprint);
        run.setCacheHit(lookup.isHit());
        run.setCacheReason(lookup.getReason().name());
        if (lookup.isHit()) {
            return "缓存命中: checksum=" + lookup.getChecksum();
        }
        String payload = "compiled:" + sourceContentOf(pipelineId);
        buildCacheService.put(cacheKey, fingerprint, payload);
        return "缓存未命中(" + lookup.getReason() + ")，已重新构建并写入缓存";
    }

    private String doPublish(PipelineRun run) {
        String pipelineId = run.getPipelineId();
        String content = "artifact:compiled:" + sourceContentOf(pipelineId);
        String source = "pipeline://" + pipelineId + "/run/" + run.getRunId();
        Artifact artifact = artifactService.registerNext(pipelineId, content, source);
        releaseService.release(pipelineId, artifact.getVersion());
        run.setProducedVersion(artifact.getVersion());
        return "发布版本 " + artifact.getVersion() + ", checksum=" + artifact.getChecksum();
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
