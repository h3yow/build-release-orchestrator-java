package com.github.h3yow.buildreleaseorchestratorjava.domain;

/**
 * 阶段执行状态。
 */
public enum StageStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED
}
