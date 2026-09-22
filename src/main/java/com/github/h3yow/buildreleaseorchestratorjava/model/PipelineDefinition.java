package com.github.h3yow.buildreleaseorchestratorjava.model;

import java.util.List;

public record PipelineDefinition(
        String pipelineId,
        String name,
        List<StageName> stages
) {
    public PipelineDefinition {
        stages = List.copyOf(stages);
    }
}
