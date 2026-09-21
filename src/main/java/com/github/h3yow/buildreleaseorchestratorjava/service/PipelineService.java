package com.github.h3yow.buildreleaseorchestratorjava.service;

import java.util.List;

import com.github.h3yow.buildreleaseorchestratorjava.domain.PipelineRun;
import org.springframework.stereotype.Service;

/**
 * 流水线编排：阶段按序执行、失败即停、断点重试、幂等触发、并发一致。
 */
@Service
public class PipelineService {

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
     * @param failAtStage 测试用故障注入点，为 null 表示不注入
     */
    public TriggerResult trigger(String pipelineId, String idempotencyKey, String failAtStage) {
        // TODO: 实现
        return null;
    }

    /**
     * 从失败点重试：已成功阶段不得重复执行或重复计时。
     */
    public PipelineRun retry(String runId) {
        // TODO: 实现
        return null;
    }

    public PipelineRun getRun(String runId) {
        // TODO: 实现
        return null;
    }

    public List<PipelineRun> listRuns() {
        // TODO: 实现
        return null;
    }
}
