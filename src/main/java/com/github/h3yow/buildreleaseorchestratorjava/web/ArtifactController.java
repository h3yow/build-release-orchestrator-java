package com.github.h3yow.buildreleaseorchestratorjava.web;

import java.util.List;
import java.util.Map;

import com.github.h3yow.buildreleaseorchestratorjava.domain.Artifact;
import com.github.h3yow.buildreleaseorchestratorjava.service.ArtifactService;
import com.github.h3yow.buildreleaseorchestratorjava.service.BuildCacheService;
import com.github.h3yow.buildreleaseorchestratorjava.service.ReleaseService;
import com.github.h3yow.buildreleaseorchestratorjava.web.dto.RollbackRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 制品查询、回滚与缓存统计入口。
 */
@RestController
@RequestMapping("/api")
public class ArtifactController {

    private final ArtifactService artifactService;
    private final ReleaseService releaseService;
    private final BuildCacheService buildCacheService;

    public ArtifactController(ArtifactService artifactService, ReleaseService releaseService,
                              BuildCacheService buildCacheService) {
        this.artifactService = artifactService;
        this.releaseService = releaseService;
        this.buildCacheService = buildCacheService;
    }

    @GetMapping("/artifacts")
    public List<Artifact> list(@RequestParam String pipelineId) {
        return artifactService.list(pipelineId);
    }

    @PostMapping("/pipelines/{pipelineId}/rollback")
    public ReleaseService.ReleaseState rollback(@PathVariable String pipelineId, @RequestBody RollbackRequest request) {
        return releaseService.rollback(pipelineId, request.getTargetVersion());
    }

    @GetMapping("/pipelines/{pipelineId}/release")
    public Map<String, String> currentRelease(@PathVariable String pipelineId) {
        return Map.of("pipelineId", pipelineId,
                "currentVersion", String.valueOf(releaseService.currentVersion(pipelineId)));
    }

    @GetMapping("/cache/stats")
    public BuildCacheService.CacheStats cacheStats() {
        return buildCacheService.stats();
    }
}
