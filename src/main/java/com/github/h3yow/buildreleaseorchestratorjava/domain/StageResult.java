package com.github.h3yow.buildreleaseorchestratorjava.domain;

import java.time.Instant;

/**
 * 单个阶段的执行结果，保留耗时与失败详情，供查询与重试断点续跑。
 */
public class StageResult {
    private final String name;
    private volatile StageStatus status;
    private volatile long durationMillis;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile String detail;

    public StageResult(String name) {
        this.name = name;
        this.status = StageStatus.PENDING;
        this.durationMillis = 0L;
    }

    public String getName() { return name; }
    public StageStatus getStatus() { return status; }
    public void setStatus(StageStatus status) { this.status = status; }
    public long getDurationMillis() { return durationMillis; }
    public void setDurationMillis(long durationMillis) { this.durationMillis = durationMillis; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
