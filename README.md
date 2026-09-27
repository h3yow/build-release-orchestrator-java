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

## 构建输入（触发身份）

- 触发请求可携带 `sourceRef`（源码标识，如 `git://repo#main`）与 `sourceContent`（源码内容），
  二者共同构成一次构建的身份（构建指纹 = SHA-256(sourceRef + 换行 + sourceContent)），
  决定缓存能否复用与最终制品内容。缺省时按流水线派生默认值，兼容旧触发方式。
- 用完全相同的输入再次触发：复用缓存中的构建结果，不重复构建；
  多次运行产出的制品内容与校验和保持一致（版本号各自推进）。
- 输入任一变化（sourceRef 或 sourceContent）：不得复用旧结果，判定为 `MISS_INCOMPATIBLE`，
  移除旧条目、重新构建并写回缓存。
- 每次运行的判定结果记录在运行记录的 `cacheHit`/`cacheReason` 中，
  通过 `GET /api/runs/{runId}` 即可从正常触发路径观察，无需绕开入口直接操作缓存。

## 版本规则

- 自动版本格式为 `1.0.N`，每条流水线独立计数，沿“自动版本水位”单调递增、永不复用、唯一。
- 版本分配与制品落库在同一临界区完成（`ArtifactService.registerNext`），
  失败不消耗版本号，因此不会出现版本跳变或半更新状态。
- 触发可携带 `requestedVersion` 指定期望产出的版本（如 `2.0.0`、`1.0.3`）：
  - **指定版本与自动版本的衔接**：指定版本注册成功后，若它落在自动号段 `1.0.N`，
    自动版本水位会单调推进到 `N`（`2.0.0` 这类非自动号段不影响水位）。
    此后普通触发（不带 `requestedVersion`）分配的永远是水位之后、当前未被占用的版本，
    因此“先指定 `1.0.1` 再普通触发”会正常拿到 `1.0.2` 并持续推进，不会因为撞号而失败，
    更不会出现一次冲突后流水线永久发不出版本的情况。普通触发沿水位单调向后，不回填历史空洞。
  - 该版本已被占用时按冲突拒绝（HTTP 409），并区分原因：
    - `VERSION_ALREADY_EXISTS`：同名版本已存在且内容/校验和一致；
    - `CHECKSUM_MISMATCH`：同名版本已存在但内容/校验和不一致；
  - 被拒绝时不消耗版本号、不推进自动版本水位、不覆盖已有制品、不移动当前发布指向，
    因此也不改变后续普通触发可用的版本号；运行标记为 `FAILED` 且失败原因可查询，不留半更新状态。
- 连续触发、并发触发以及“先指定版本再普通触发”混合使用时，版本号在同一流水线锁下分配，
  全局唯一；任何一次指定版本被拒绝都不会打乱后续版本推进。
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

- 缓存键为 `<pipelineId>::build`，命中判定依据构建指纹（`sourceRef` + `sourceContent` 的 SHA-256，
  缺省输入时按流水线派生）。
- 查询结果可解释：`HIT` / `MISS_NO_ENTRY`（从未构建过）/ `MISS_CORRUPTED`（缓存内容已损坏）/
  `MISS_INCOMPATIBLE`（与当前输入不兼容），并计入运行记录的 `cacheHit`/`cacheReason` 供查询。
- 每次查询重新计算条目校验和：损坏（`MISS_CORRUPTED`）或指纹不兼容（`MISS_INCOMPATIBLE`）
  的条目会被识别、移除并触发重建，绝不静默复用。
- 淘汰策略由 `orchestrator.cache-eviction-policy` 配置，支持两种且行为可切换：
  - `LRU`（默认）：淘汰最久未使用的条目（命中会刷新使用顺序）；
  - `FIFO`：淘汰最早插入的条目（命中不影响顺序）。
  配置为无法识别的值时启动即失败（`INVALID_EVICTION_POLICY`），不会静默退回默认策略。
- 容量由 `orchestrator.cache-capacity` 控制；所有缓存操作在内部锁下完成，
  并发读写下容量不被突破、无中间态。当前条目数、命中/未命中/淘汰次数与生效策略
  通过 `GET /api/cache/stats` 可查询。

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
| `orchestrator.cache-capacity` | 50 | 按配置的淘汰策略淘汰 |
| `orchestrator.cache-eviction-policy` | LRU | 支持 `LRU`/`FIFO`；无法识别的值启动即失败（`INVALID_EVICTION_POLICY`） |

任何超限失败都不会残留部分状态或泄漏资源。

## API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/pipelines/{id}/trigger` | 触发流水线，body: `{"idempotencyKey":"k","failAtStage":"TEST","sourceRef":"git://repo#main","sourceContent":"...","requestedVersion":"2.0.0"}`（后四项可选） |
| POST | `/api/runs/{runId}/retry` | 从失败点重试 |
| GET | `/api/runs/{runId}` | 查询运行状态、阶段耗时、缓存命中情况（`cacheHit`/`cacheReason`） |
| GET | `/api/runs` | 运行列表 |
| GET | `/api/artifacts?pipelineId=` | 制品列表（版本/校验和/来源） |
| POST | `/api/pipelines/{id}/rollback` | 回滚，body: `{"targetVersion":"1.0.1"}` |
| GET | `/api/pipelines/{id}/release` | 当前发布版本 |
| GET | `/api/cache/stats` | 缓存条目数/命中/未命中/淘汰次数/生效淘汰策略 |

## 本地验证方法

1. `mvn test`：覆盖并发与重复触发、失败重试、缓存损坏、版本冲突、回滚与上限场景，
   以及构建输入复用/重建判定、FIFO/LRU 淘汰差异、非法淘汰配置拒绝、
   指定版本冲突拒绝后版本号与发布指向保持不变；
   `MixedVersionTriggerTest` 额外稳定覆盖“指定版本成功后再普通触发仍能正常出版本”
   “指定版本（两种冲突原因）被拒绝后再普通触发仍单调推进”“连续/并发混合触发版本号全局唯一”。
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
3. 构建输入与缓存判定（正常触发路径可观察）：
   ```bash
   # 携带构建输入触发，首次 cacheReason=MISS_NO_ENTRY
   curl -X POST localhost:8080/api/pipelines/demo/trigger \
     -H 'Content-Type: application/json' \
     -d '{"idempotencyKey":"b1","sourceRef":"git://repo#main","sourceContent":"v1"}'
   # 相同输入再次触发（换幂等键）：cacheHit=true, cacheReason=HIT，制品校验和与首次一致
   curl -X POST localhost:8080/api/pipelines/demo/trigger \
     -H 'Content-Type: application/json' \
     -d '{"idempotencyKey":"b2","sourceRef":"git://repo#main","sourceContent":"v1"}'
   # 改变输入：cacheReason=MISS_INCOMPATIBLE，重新构建并写回
   curl -X POST localhost:8080/api/pipelines/demo/trigger \
     -H 'Content-Type: application/json' \
     -d '{"idempotencyKey":"b3","sourceRef":"git://repo#main","sourceContent":"v2"}'
   # 查询运行记录中的缓存判定
   curl localhost:8080/api/runs/<runId>
   # 查询缓存条目数/淘汰次数/生效策略
   curl localhost:8080/api/cache/stats
   ```
4. 指定版本与冲突、以及与普通自动版本的衔接：
   ```bash
   # 指定一个落在自动号段的版本触发，产出 1.0.1
   curl -X POST localhost:8080/api/pipelines/mix/trigger \
     -H 'Content-Type: application/json' \
     -d '{"idempotencyKey":"v1","requestedVersion":"1.0.1"}'
   # 同版本同内容：409 VERSION_ALREADY_EXISTS；换 sourceContent 则为 409 CHECKSUM_MISMATCH
   curl -i -X POST localhost:8080/api/pipelines/mix/trigger \
     -H 'Content-Type: application/json' \
     -d '{"idempotencyKey":"v2","requestedVersion":"1.0.1"}'
   # 关键衔接：被拒绝后再走普通触发（不带 requestedVersion），应成功拿到 1.0.2，
   # 而不会再次撞号；之后每次普通触发继续得到 1.0.3、1.0.4 …
   curl -X POST localhost:8080/api/pipelines/mix/trigger \
     -H 'Content-Type: application/json' -d '{"idempotencyKey":"v3"}'
   curl -X POST localhost:8080/api/pipelines/mix/trigger \
     -H 'Content-Type: application/json' -d '{"idempotencyKey":"v4"}'
   # 发布指向随成功的普通触发推进到 1.0.3；被拒绝的那次不会移动它
   curl localhost:8080/api/pipelines/mix/release
   # 指定非自动号段（如 2.0.0）不影响 1.0.N 水位：下一次普通触发仍是后续的 1.0.N
   ```
5. 淘汰策略切换：在 `application.properties` 设置
   `orchestrator.cache-eviction-policy=FIFO`（或 `LRU`）后重启即可生效；
   设置为其他值时应用启动失败并提示 `INVALID_EVICTION_POLICY`。
6. 日志中可观察关键输入与判定依据：幂等键、构建输入、版本、校验和、缓存命中原因、
   淘汰策略与并发冲突。
