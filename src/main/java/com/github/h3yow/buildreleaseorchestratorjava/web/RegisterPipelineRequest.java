package com.github.h3yow.buildreleaseorchestratorjava.web;

import java.util.List;

public class RegisterPipelineRequest {

    private String pipelineId;
    private String name;
    private List<String> stages = List.of();

    public String getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(String pipelineId) {
        this.pipelineId = pipelineId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getStages() {
        return stages;
    }

    public void setStages(List<String> stages) {
        this.stages = stages;
    }
}
