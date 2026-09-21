package com.github.h3yow.buildreleaseorchestratorjava.web;

import java.util.List;

import com.github.h3yow.buildreleaseorchestratorjava.domain.PipelineRun;
import com.github.h3yow.buildreleaseorchestratorjava.service.PipelineService;
import com.github.h3yow.buildreleaseorchestratorjava.web.dto.TriggerRequest;
import com.github.h3yow.buildreleaseorchestratorjava.web.dto.TriggerResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流水线触发、重试与运行状态查询入口。
 */
@RestController
@RequestMapping("/api")
public class PipelineController {

    private final PipelineService pipelineService;

    public PipelineController(PipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @PostMapping("/pipelines/{pipelineId}/trigger")
    public TriggerResponse trigger(@PathVariable String pipelineId, @RequestBody TriggerRequest request) {
        PipelineService.TriggerResult result =
                pipelineService.trigger(pipelineId, request.getIdempotencyKey(), request.getFailAtStage());
        return new TriggerResponse(result.getRun(), result.isDuplicated());
    }

    @PostMapping("/runs/{runId}/retry")
    public PipelineRun retry(@PathVariable String runId) {
        return pipelineService.retry(runId);
    }

    @GetMapping("/runs/{runId}")
    public PipelineRun getRun(@PathVariable String runId) {
        return pipelineService.getRun(runId);
    }

    @GetMapping("/runs")
    public List<PipelineRun> listRuns() {
        return pipelineService.listRuns();
    }
}
