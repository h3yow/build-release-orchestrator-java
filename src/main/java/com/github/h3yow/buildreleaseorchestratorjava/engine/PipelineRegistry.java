package com.github.h3yow.buildreleaseorchestratorjava.engine;

import com.github.h3yow.buildreleaseorchestratorjava.error.ErrorCode;
import com.github.h3yow.buildreleaseorchestratorjava.error.OrchestratorException;
import com.github.h3yow.buildreleaseorchestratorjava.model.PipelineDefinition;
import com.github.h3yow.buildreleaseorchestratorjava.model.StageName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PipelineRegistry {

    private static final Logger log = LoggerFactory.getLogger(PipelineRegistry.class);

    private final Map<String, PipelineDefinition> pipelines = new ConcurrentHashMap<>();

    public PipelineDefinition register(PipelineDefinition definition) {
        PipelineDefinition previous = pipelines.putIfAbsent(definition.pipelineId(), definition);
        if (previous != null && !previous.equals(definition)) {
            throw new OrchestratorException(ErrorCode.VERSION_CONFLICT,
                    "pipeline already registered with a different definition: "
                            + definition.pipelineId());
        }
        log.info("pipeline registered pipelineId={} stages={}",
                definition.pipelineId(), definition.stages());
        return definition;
    }

    public PipelineDefinition define(String pipelineId, String name, List<String> stageNames) {
        if (pipelineId == null || pipelineId.isBlank()) {
            throw new OrchestratorException(ErrorCode.ILLEGAL_STATE, "pipelineId is required");
        }
        List<StageName> stages = new ArrayList<>();
        for (String raw : stageNames) {
            try {
                stages.add(StageName.valueOf(raw.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new OrchestratorException(ErrorCode.ILLEGAL_STATE, "unknown stage: " + raw);
            }
        }
        if (stages.isEmpty()) {
            throw new OrchestratorException(ErrorCode.ILLEGAL_STATE,
                    "at least one stage is required for pipeline " + pipelineId);
        }
        return register(new PipelineDefinition(pipelineId, name == null ? pipelineId : name, stages));
    }

    public PipelineDefinition require(String pipelineId) {
        PipelineDefinition definition = pipelines.get(pipelineId);
        if (definition == null) {
            throw new OrchestratorException(ErrorCode.PIPELINE_NOT_FOUND,
                    "pipeline not found: " + pipelineId);
        }
        return definition;
    }

    public List<PipelineDefinition> list() {
        return List.copyOf(pipelines.values());
    }
}
