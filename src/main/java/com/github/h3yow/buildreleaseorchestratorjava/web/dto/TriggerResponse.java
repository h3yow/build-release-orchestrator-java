package com.github.h3yow.buildreleaseorchestratorjava.web.dto;

import com.github.h3yow.buildreleaseorchestratorjava.domain.PipelineRun;

/**
 * 触发响应：duplicated 区分重复提交与首次执行。
 */
public class TriggerResponse {
    private final PipelineRun run;
    private final boolean duplicated;

    public TriggerResponse(PipelineRun run, boolean duplicated) {
        this.run = run;
        this.duplicated = duplicated;
    }

    public PipelineRun getRun() { return run; }
    public boolean isDuplicated() { return duplicated; }
}
