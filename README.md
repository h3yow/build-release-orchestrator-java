# 本地构建发布编排器

在本地模拟一套构建发布流水线能力：流水线运行与阶段状态、幂等触发、制品与版本管理、
构建缓存、失败重试与回滚。全部状态保存在内存中，不依赖外部服务或真实凭据。

## 构建与测试

```bash
mvn compile      # 编译
mvn test         # 运行全部测试（并发、重试、缓存损坏、版本冲突、回滚、上限）
mvn spring-boot:run  # 本地启动，默认端口 8080
```

## 流水线模型

阶段固定按序执行：`CHECKOUT -> BUILD(含缓存判定) -> TEST -> PACKAGE -> PUBLISH`。

- 失败即停：失败阶段之后的状态保持 `PENDING`，运行标记为 `FAILED` 并保留全部阶段状态供查询。
- 断点重试：`POST /api/runs/{runId}/retry` 从失败点继续；已成功阶段跳过，不重复执行、不重复计时
  （`startedAt`/`durationMillis` 保持不变）。仅 `FAILED` 状态的运行可重试，否则返回 `RUN_NOT_FAILED`。
- 故障注入（本地模拟）：触发请求可携带 `failAtStage`，为一次性注入，在阶段产生任何副作用前生效，
  因此失败不会留下半更新状态；重试时注入已消费，不再生效。

## 版本规则

- 版本格式为 `1.0.N`，每条流水线独立计数，单调递增、唯一。
- 版本分配与制品落库在同一临界区完成（`ArtifactService.registerNext`），
  失败不消耗版本号，因此不会出现版本跳变或半更新状态。
- 同名版本重复注册一律拒绝，并区分原因：
  - `VERSION_ALREADY_EXISTS`：版本已存在且校验和一致；
  - `CHECKSUM_MISMATCH`：版本已存在但校验和不一致（原有制品不被覆盖）。
- 每个制品记录 `source`（来源，如 `pipeline://<pipeline>/run/<runId>`）与 SHA-256 `checksum`，可追溯、可复算。

## 幂等与并发语义

- 触发必须携带 `idempotencyKey`。同一 `(pipelineId, idempotencyKey)` 只产生一次真实运行；
  重复提交返回首次运行且响应中 `duplicated=true`，与首次请求可区分。
  首次执行若抛异常则不登记，允许同键重试。
- 幂等登记基于 `ConcurrentHashMap.compute` 原子完成，并发下同键只有一个线程真正执行。
- 同一流水线的运行按流水线级锁串行执行，并发触发不会产生重复运行、版本跳变或半更新状态。
- 冲突（版本冲突、回滚拒绝、超限、非法重试）以 HTTP 409 + 原因码明确拒绝。

## 构建缓存

- 缓存键为 `<pipelineId>::build`，命中判定依据构建指纹（源码内容的 SHA-256）。
- 查询结果可解释：`HIT` / `MISS_NO_ENTRY` / `MISS_CORRUPTED` / `MISS_INCOMPATIBLE`，
  并计入运行记录的 `cacheHit`/`cacheReason` 供查询。
- 每次查询重新计算条目校验和：损坏（`MISS_CORRUPTED`）或指纹不兼容（`MISS_INCOMPATIBLE`）
  的条目会被识别、移除并触发重建，绝不静默复用。
- 淘汰策略由 `orchestrator.cache-eviction-policy` 配置（当前支持 `LRU`），
  容量由 `orchestrator.cache-capacity` 控制；所有缓存操作在内部锁下完成，并发安全。

## 回滚策略

- `POST /api/pipelines/{id}/rollback` 将发布指针切回历史版本。
- 幂等：目标版本已是当前版本时不产生变更（`changed=false`）。
- 目标制品不存在（`ARTIFACT_NOT_FOUND`）或校验和不一致（`CHECKSUM_MISMATCH`）时拒绝，
  版本指向保持不变。
- 回滚只移动发布指针，不修改制品内容与校验和，指向与制品始终一致。

## 可配置上限（application.properties）

| 配置 | 默认 | 超限行为 |
| --- | --- | --- |
| `orchestrator.max-runs` | 100 | 淘汰最早已完成运行；无可淘汰则拒绝（`LIMIT_EXCEEDED`） |
| `orchestrator.max-artifacts-per-pipeline` | 10 | 淘汰最旧非当前发布制品；无可淘汰则拒绝并回滚本次注册 |
| `orchestrator.cache-capacity` | 50 | 按 LRU 淘汰 |
| `orchestrator.cache-eviction-policy` | LRU | 未知策略按 LRU 处理并告警 |

任何超限失败都不会残留部分状态或泄漏资源。

## API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/pipelines/{id}/trigger` | 触发流水线，body: `{"idempotencyKey":"k","failAtStage":"TEST"}` |
| POST | `/api/runs/{runId}/retry` | 从失败点重试 |
| GET | `/api/runs/{runId}` | 查询运行状态、阶段耗时、缓存命中情况 |
| GET | `/api/runs` | 运行列表 |
| GET | `/api/artifacts?pipelineId=` | 制品列表（版本/校验和/来源） |
| POST | `/api/pipelines/{id}/rollback` | 回滚，body: `{"targetVersion":"1.0.1"}` |
| GET | `/api/pipelines/{id}/release` | 当前发布版本 |
| GET | `/api/cache/stats` | 缓存条目数/命中/未命中/淘汰次数 |

## 本地验证方法

1. `mvn test`：覆盖并发与重复触发、失败重试、缓存损坏、版本冲突、回滚与上限场景，
   每个用例打印输入与判定依据（`[test] 输入/判定依据`），断言结论可区分。
2. 手动验证：`mvn spring-boot:run` 后：
   ```bash
   # 触发并注入 TEST 阶段失败
   curl -X POST localhost:8080/api/pipelines/demo/trigger \
     -H 'Content-Type: application/json' \
     -d '{"idempotencyKey":"k1","failAtStage":"TEST"}'
   # 重复提交同一幂等键，观察 duplicated=true 且 runId 相同
   curl -X POST localhost:8080/api/pipelines/demo/trigger \
     -H 'Content-Type: application/json' -d '{"idempotencyKey":"k1"}'
   # 用返回的 runId 重试，观察已成功阶段耗时不变
   curl -X POST localhost:8080/api/runs/<runId>/retry
   # 再发一版后回滚
   curl -X POST localhost:8080/api/pipelines/demo/trigger \
     -H 'Content-Type: application/json' -d '{"idempotencyKey":"k2"}'
   curl -X POST localhost:8080/api/pipelines/demo/rollback \
     -H 'Content-Type: application/json' -d '{"targetVersion":"1.0.1"}'
   ```
3. 日志中可观察关键输入与判定依据：幂等键、版本、校验和、缓存命中原因与并发冲突。
