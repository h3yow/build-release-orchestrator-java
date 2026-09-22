package com.github.h3yow.buildreleaseorchestratorjava.web;

import com.github.h3yow.buildreleaseorchestratorjava.engine.OrchestratorService;
import com.github.h3yow.buildreleaseorchestratorjava.model.Artifact;
import com.github.h3yow.buildreleaseorchestratorjava.model.CacheEntry;
import com.github.h3yow.buildreleaseorchestratorjava.model.PipelineDefinition;
import com.github.h3yow.buildreleaseorchestratorjava.model.PipelineRun;
import com.github.h3yow.buildreleaseorchestratorjava.model.ReleaseState;
import com.github.h3yow.buildreleaseorchestratorjava.model.TriggerResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orchestrator")
public class OrchestratorController {

    private final OrchestratorService service;

    public OrchestratorController(OrchestratorService service) {
        this.service = service;
    }

    @PostMapping("/pipelines")
    public PipelineDefinition register(@RequestBody RegisterPipelineRequest request) {
        return service.registerPipeline(request.getPipelineId(), request.getName(), request.getStages());
    }

    @GetMapping("/pipelines")
    public List<PipelineDefinition> pipelines() {
        return service.registryList();
    }

    @PostMapping("/pipelines/{pipelineId}/trigger")
    public TriggerResult trigger(@PathVariable String pipelineId, @RequestBody TriggerRequest request) {
        return service.trigger(pipelineId, request);
    }

    @PostMapping("/runs/{runId}/retry")
    public TriggerResult retry(@PathVariable String runId) {
        return service.retry(runId);
    }

    @GetMapping("/runs/{runId}")
    public PipelineRun getRun(@PathVariable String runId) {
        return service.getRun(runId);
    }

    @GetMapping("/pipelines/{pipelineId}/runs")
    public List<PipelineRun> listRuns(@PathVariable String pipelineId) {
        return service.listRuns(pipelineId);
    }

    @GetMapping("/pipelines/{pipelineId}/artifacts")
    public List<Artifact> listArtifacts(@PathVariable String pipelineId) {
        return service.listArtifacts(pipelineId);
    }

    @GetMapping("/pipelines/{pipelineId}/release")
    public ReleaseState getRelease(@PathVariable String pipelineId) {
        return service.getRelease(pipelineId);
    }

    @PostMapping("/pipelines/{pipelineId}/rollback")
    public ReleaseState rollback(@PathVariable String pipelineId,
                                 @RequestBody(required = false) RollbackRequest request) {
        String target = request == null ? null : request.getTargetVersion();
        return service.rollback(pipelineId, target);
    }

    @GetMapping("/pipelines/{pipelineId}/cache")
    public List<CacheEntry> listCache(@PathVariable String pipelineId) {
        return service.listCache(pipelineId);
    }

    @PostMapping("/pipelines/{pipelineId}/cache/corrupt")
    public Map<String, Object> corruptCache(@PathVariable String pipelineId,
                                            @RequestParam String buildInputHash) {
        boolean corrupted = service.corruptCache(pipelineId, buildInputHash);
        return Map.of("corrupted", corrupted, "buildInputHash", buildInputHash);
    }

    @PostMapping("/pipelines/{pipelineId}/artifacts/{version}/tamper")
    public Map<String, Object> tamperArtifact(@PathVariable String pipelineId,
                                              @PathVariable String version) {
        boolean tampered = service.tamperArtifact(pipelineId, version);
        return Map.of("tampered", tampered, "version", version);
    }
}
