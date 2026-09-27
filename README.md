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

## 构建输入与缓存

### 一次触发携带构建身份

触发请求可携带本次构建实际使用的输入：

- `sourceRef`：源码标识（如分支、提交或工作区标识），决定缓存命名空间；
- `sourceContent`：对应的源码内容，其 SHA-256 即构建指纹。
- 两者都缺省时使用按流水线派生的默认输入（`sourceRef=default`），兼容旧请求。

缓存键为 `<pipelineId>::build::<sourceRef>`，判定规则：

- 相同 `(sourceRef, sourceContent)` 再次触发：`HIT`，复用缓存的构建产物打包发布，
  最终制品内容与 SHA-256 校验和与首次构建**逐字节一致**，不重新构建（运行结果 `reusedBuild=true`）。
- 同一 `sourceRef` 内容变化：`MISS_INCOMPATIBLE`（与当前输入不兼容），必须重新构建并写回，校验和随之改变。
- 换了从未构建过的 `sourceRef`：`MISS_NO_ENTRY`（从未构建过）。
- 缓存条目内容与登记校验和不符：`MISS_CORRUPTED`（缓存内容已损坏），移除后重建。

四种判定都记录在运行结果的 `cacheHit`/`cacheReason` 字段中，通过正常触发路径
（`GET /api/runs/{runId}` 与触发响应）即可观察，无需绕开入口直接操作缓存。

### 淘汰策略可切换

- 支持 `FIFO`（先进先出，按首次写入顺序；覆盖同键不改变入队顺序）与
  `LRU`（最近最少使用，按最近访问时间）两种策略，切换后严格按各自语义决定淘汰谁。
- 配置项 `orchestrator.cache-eviction-policy` 决定启动策略（默认 `LRU`）；
  也可通过 `POST /api/cache/policy` 在运行时切换（body：`{"policy":"FIFO"}`）。
- 配置或切换到无法识别的策略时**明确失败**：
  启动阶段直接抛 `UNKNOWN_EVICTION_POLICY`（应用启动失败），运行时切换返回 HTTP 400，
  且原策略保持不变——不会悄悄退回默认策略继续运行。
- `GET /api/cache/stats` 返回当前条目数 `size`、命中/未命中/淘汰次数与当前 `policy`。
- 所有缓存读写在同一内部锁下完成：并发下容量不被突破，也读不到中间态条目。

## 版本规则

- 版本格式为 `1.0.N`，每条流水线独立计数，单调递增、唯一。
- 自动分配与制品落库在同一临界区完成（`ArtifactService.registerNext`），
  失败不消耗版本号，因此不会出现版本跳变或半更新状态。
- 触发时可用 `expectedVersion` 指定期望产出的版本（格式必须为 `1.0.N`，
  非法格式在产生任何运行前以 `INVALID_VERSION` 拒绝，HTTP 400）。
  该版本未被占用时按指定版本发布，并把计数器单调推进到该序号，后续自动版本号不回退。
- 期望版本已被占用时按冲突拒绝（HTTP 409），并区分两种情况：
  - `VERSION_ALREADY_EXISTS`：同名版本已存在**且内容一致**；
  - `CHECKSUM_MISMATCH`：同名版本已存在但**内容或校验和不一致**（原有制品不被覆盖）。
- 冲突拒绝时：不额外消耗版本号、不覆盖已有制品、不移动当前发布指向、不留下半更新状态；
  对应运行记录为 `FAILED`（PUBLISH 阶段失败），冲突原因码直接随触发响应返回。
- 同名版本的直接注册（`ArtifactService.register`）同样区分上述两种冲突原因。
- 每个制品记录 `source`（来源，如 `pipeline://<pipeline>/run/<runId>`）与 SHA-256 `checksum`，可追溯、可复算。

## 幂等与并发语义

- 触发必须携带 `idempotencyKey`。同一 `(pipelineId, idempotencyKey)` 只产生一次真实运行；
  重复提交返回首次运行且响应中 `duplicated=true`，与首次请求可区分。
  首次执行若抛异常则不登记，允许同键重试。
- 幂等登记基于 `ConcurrentHashMap.compute` 原子完成，并发下同键只有一个线程真正执行。
- 同一流水线的运行按流水线级锁串行执行，并发触发不会产生重复运行、版本跳变或半更新状态。
- 冲突（版本冲突、回滚拒绝、超限、非法重试）以 HTTP 409 + 原因码明确拒绝。

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
| `orchestrator.cache-capacity` | 50 | 按当前淘汰策略（FIFO/LRU）淘汰 |
| `orchestrator.cache-eviction-policy` | LRU | 仅支持 `FIFO`/`LRU`；无法识别时启动失败（`UNKNOWN_EVICTION_POLICY`），运行时切换返回 400 |

任何超限失败都不会残留部分状态或泄漏资源。

## API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/pipelines/{id}/trigger` | 触发流水线，body: `{"idempotencyKey":"k","sourceRef":"ref-a","sourceContent":"...","expectedVersion":"1.0.3","failAtStage":"TEST"}`（后三者可选） |
| POST | `/api/runs/{runId}/retry` | 从失败点重试 |
| GET | `/api/runs/{runId}` | 查询运行状态、阶段耗时、`sourceRef`/`sourceFingerprint`、缓存命中原因与 `reusedBuild` |
| GET | `/api/runs` | 运行列表 |
| GET | `/api/artifacts?pipelineId=` | 制品列表（版本/校验和/来源） |
| POST | `/api/pipelines/{id}/rollback` | 回滚，body: `{"targetVersion":"1.0.1"}` |
| GET | `/api/pipelines/{id}/release` | 当前发布版本 |
| GET | `/api/cache/stats` | 当前策略、缓存条目数/命中/未命中/淘汰次数 |
| POST | `/api/cache/policy` | 运行时切换淘汰策略，body: `{"policy":"FIFO"}`；未知策略 400 + `UNKNOWN_EVICTION_POLICY` |

## 本地验证方法

1. `mvn test`：共 39 个用例，覆盖
   并发与重复触发、失败重试、缓存损坏、版本冲突、回滚、上限等既有场景（无回归），以及新增的
   输入不变复用/输入变化重建（`HIT`/`MISS_NO_ENTRY`/`MISS_CORRUPTED`/`MISS_INCOMPATIBLE`）、
   FIFO 与 LRU 行为差异、未知淘汰策略拒绝、期望版本冲突后版本号与发布指针不变。
   每个用例打印输入与判定依据（`[test] 输入/判定依据`），断言结论可区分。
2. 手动验证：`mvn spring-boot:run` 后：
   ```bash
   # 首次构建：cacheReason=MISS_NO_ENTRY
   curl -X POST localhost:8080/api/pipelines/demo/trigger \
     -H 'Content-Type: application/json' \
     -d '{"idempotencyKey":"b1","sourceRef":"ref-a","sourceContent":"src-v1"}'
   # 相同输入再触发：cacheReason=HIT, reusedBuild=true，两次制品 checksum 一致
   curl -X POST localhost:8080/api/pipelines/demo/trigger \
     -H 'Content-Type: application/json' \
     -d '{"idempotencyKey":"b2","sourceRef":"ref-a","sourceContent":"src-v1"}'
   # 内容变化：MISS_INCOMPATIBLE；换新 sourceRef：MISS_NO_ENTRY
   curl -X POST localhost:8080/api/pipelines/demo/trigger \
     -H 'Content-Type: application/json' \
     -d '{"idempotencyKey":"b3","sourceRef":"ref-a","sourceContent":"src-v2"}'
   curl -s "localhost:8080/api/artifacts?pipelineId=demo"   # 对比各版本 checksum
   # 切换 FIFO / 未知策略被 400 拒绝
   curl -X POST localhost:8080/api/cache/policy -H 'Content-Type: application/json' -d '{"policy":"FIFO"}'
   curl -i -X POST localhost:8080/api/cache/policy -H 'Content-Type: application/json' -d '{"policy":"RANDOM"}'
   curl -s localhost:8080/api/cache/stats                    # size/policy/淘汰次数
   # 指定已占用版本：409 VERSION_ALREADY_EXISTS 或 CHECKSUM_MISMATCH，且不消耗版本号
   curl -i -X POST localhost:8080/api/pipelines/demo/trigger \
     -H 'Content-Type: application/json' \
     -d '{"idempotencyKey":"b5","sourceRef":"ref-a","sourceContent":"src-v1","expectedVersion":"1.0.1"}'
   # 失败注入与断点重试
   curl -X POST localhost:8080/api/pipelines/demo/trigger \
     -H 'Content-Type: application/json' \
     -d '{"idempotencyKey":"k1","failAtStage":"TEST"}'
   curl -X POST localhost:8080/api/runs/<runId>/retry
   ```
3. 日志中可观察关键输入与判定依据：幂等键、源码标识与指纹、版本、校验和、
   缓存命中原因、淘汰策略与对象、并发冲突。
