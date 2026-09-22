package com.github.h3yow.buildreleaseorchestratorjava.model;

public enum TriggerOutcome {
    STARTED,
    DUPLICATE_REPLAY,
    REJECTED_CONFLICT,
    REJECTED_LIMIT,
    FAILED
}
