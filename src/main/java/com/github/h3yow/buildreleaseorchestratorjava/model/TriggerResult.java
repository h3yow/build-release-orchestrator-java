package com.github.h3yow.buildreleaseorchestratorjava.model;

public class TriggerResult {

    private final TriggerOutcome outcome;
    private final String runId;
    private final String pipelineId;
    private final String idempotencyKey;
    private final String version;
    private final RunStatus runStatus;
    private final String message;
    private final ErrorView error;

    public TriggerResult(TriggerOutcome outcome, String runId, String pipelineId, String idempotencyKey,
                         String version, RunStatus runStatus, String message, ErrorView error) {
        this.outcome = outcome;
        this.runId = runId;
        this.pipelineId = pipelineId;
        this.idempotencyKey = idempotencyKey;
        this.version = version;
        this.runStatus = runStatus;
        this.message = message;
        this.error = error;
    }

    public static TriggerResult replay(PipelineRun run) {
        return new TriggerResult(TriggerOutcome.DUPLICATE_REPLAY, run.getRunId(), run.getPipelineId(),
                run.getIdempotencyKey(), run.getResolvedVersion(), run.getStatus(),
                "duplicate idempotency key, replaying original result", null);
    }

    public TriggerOutcome getOutcome() {
        return outcome;
    }

    public String getRunId() {
        return runId;
    }

    public String getPipelineId() {
        return pipelineId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getVersion() {
        return version;
    }

    public RunStatus getRunStatus() {
        return runStatus;
    }

    public String getMessage() {
        return message;
    }

    public ErrorView getError() {
        return error;
    }
}
