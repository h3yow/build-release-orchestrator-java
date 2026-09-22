# 本地构建发布编排器（build-release-orchestrator-java）

一个仅依赖 Spring Boot 自带能力、在单进程内存中模拟构建/发布流水线的编排服务。
不连接任何外部服务、不使用真实凭据：制品内容、校验和（SHA-256）、构建缓存、
版本序列、发布指针全部在本地确定性生成并保存于内存中。

## 能力总览

- 流水线按定义顺序执行阶段（`CHECKOUT -> BUILD -> TEST -> PACKAGE -> RELEASE`），
  任一阶段失败即停止，后续阶段保持 `PENDING`，阶段状态与耗时可查询。
- 失败重试从失败点继续：已 `SUCCEEDED` 的阶段不会被重复执行，其状态与耗时
  （`durationMillis`）不会被重新计时或覆盖。
- 基于业务幂等键的幂等触发：重复提交返回 `DUPLICATE_REPLAY`（与首次 `STARTED`
  可区分），不产生重复运行、重复制品或重复版本推进。
- 版本唯一且单调推进；同名版本冲突被拒绝，并区分「版本已存在 / 非单调」与
  「制品校验和不一致」两类原因；制品来源运行（`sourceRunId`）、输入哈希
  （`buildInputHash`）与校验和（`checksum`）可追溯。
- 构建缓存可解释：`MISS` / `HIT:verified` / `INCOMPATIBLE` / `CORRUPTED`；
  损坏或与当前构建不兼容的缓存绝不静默复用，会被剔除并重新构建；容量与
  淘汰策略（LRU/FIFO）可配置且并发安全。
- 并发触发同一流水线或同一版本时结果一致：每个流水线一把公平锁串行化
  触发/重试/回滚，杜绝重复运行、版本跳变与半更新状态。
- 发布与回滚在移动指针前校验制品是否存在及校验和，失败不留下半更新状态；
  回滚幂等（回到当前版本是空操作），回滚到不存在或损坏的制品会被拒绝。
- 运行数量、制品保留量、缓存容量均有可配置上限，超限按「拒绝」或「淘汰」
  处理且不残留部分状态。

## 版本规则

- 版本格式固定为 `1.0.N`（`N >= 1`），每个流水线独立计数，从 `1.0.1` 开始。
- 自动分配：不传 `requestedVersion` 时取当前序列的下一个值。
- 显式分配：传入的版本必须恰好等于下一个单调版本，否则返回
  `VERSION_CONFLICT`（不允许跳号或回填）。
- **版本只在 `PACKAGE` 阶段真正产出制品时才提交序列**。在 `PACKAGE` 之前失败的
  运行不会消耗版本号，因此「失败 -> 重试 / 重提」不会造成版本跳变。
- 同一版本注册第二份但内容不同的制品时返回 `ARTIFACT_CHECKSUM_MISMATCH`；
  版本号本身已被占用但序列层面冲突时返回 `VERSION_ALREADY_EXISTS` /
  `VERSION_CONFLICT`。同版本同校验和的重复注册是幂等空操作。
- 制品内容校验：注册前先对内容计算 SHA-256 并与传入校验和比对；
  发布/回滚前再次 `verify`，损坏内容（校验和不符）一律拒绝。

## 并发语义

- 每个 `pipelineId` 对应一把**公平可重入锁**，`trigger` / `retry` / `rollback`
  对同一流水线互斥；不同流水线互不阻塞。
- 幂等索引（`idempotencyKey -> runId`）在同一临界区内检查与写入：
  同一幂等键并发提交，恰好一个产生 `STARTED` 运行，其余全部得到
  `DUPLICATE_REPLAY` 且指向同一 `runId`。
- 多个不同幂等键并发提交同一流水线时，版本在锁内单调分配并在制品注册成功时
  提交，结果为连续的 `1.0.1 ... 1.0.N`，无空洞、无重复、无跳变。
- 在执行期才发现的「拒绝性」错误（版本/校验和冲突、上限拒绝）会向上抛出，
  编排器随即回滚刚登记的运行与幂等占位，保证被拒绝的请求不残留任何状态。

## 缓存策略

- 缓存键：`pipelineId + ":" + sha256(branch + "|" + sources)`（构建输入哈希）。
- 兼容标签 `compatibilityTag`（默认 `builder-1`）是独立维度，不参与缓存键；
  标签不同命中时判定为 `INCOMPATIBLE` 并重新构建，而不是普通 `MISS`。
- 命中判定顺序：无条目 `MISS` → 校验和不符 `CORRUPTED`（立即剔除并重建）→
  标签不符 `INCOMPATIBLE`（重建）→ `HIT:verified`（复用）。
- 容量 `orchestrator.max-cache-entries` 为全局条目上限；
  淘汰策略 `orchestrator.cache-eviction-policy` 支持 `LRU`（默认，按最近访问）
  与 `FIFO`（按创建时间），所有变更经同一把锁串行化，并发安全。
- BUILD 阶段的命中布尔值与原因记录在阶段状态中（`cacheHit` / `cacheReason`）。

## 制品保留与回滚策略

- 制品按流水线保留，上限 `orchestrator.max-artifacts-per-pipeline`。
- 注册新制品达到上限时先按「滚动窗口」淘汰最低（最旧）版本；`RELEASE` 成功、
  发布指针移动后再做一次带指针保护的收敛：当前版本与上一版本受保护、不会被
  淘汰，从而保证回滚目标可用。
- 发布指针记录 `currentVersion/currentChecksum` 与
  `previousVersion/previousChecksum`。
- 回滚：
  - 指定目标版本：校验制品存在（不存在 → `ROLLBACK_TARGET_UNAVAILABLE`）且
    校验和一致（损坏 → `ARTIFACT_CHECKSUM_MISMATCH`），通过后交换指针。
  - 不指定目标：回到 `previousVersion`；若从未发布过历史版本则拒绝。
  - 目标即当前版本时为幂等空操作，仍返回成功。
  - 任何失败都不改动当前指针（无半更新）。

## 配置项（`src/main/resources/application.properties`）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `orchestrator.max-runs-per-pipeline` | `100` | 每条流水线保留的运行上限，超限拒绝新触发 |
| `orchestrator.max-artifacts-per-pipeline` | `20` | 每条流水线制品保留量，超限淘汰最旧未受保护制品 |
| `orchestrator.max-cache-entries` | `50` | 全局构建缓存条目上限 |
| `orchestrator.cache-eviction-policy` | `LRU` | 缓存淘汰策略：`LRU` 或 `FIFO` |

应用启动时自动注册一个 `default` 流水线（五个阶段齐全）。

## HTTP 接口

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/orchestrator/pipelines` | 注册流水线（body: pipelineId/name/stages） |
| `GET  /api/orchestrator/pipelines` | 列出流水线 |
| `POST /api/orchestrator/pipelines/{id}/trigger` | 幂等触发（body 见下） |
| `POST /api/orchestrator/runs/{runId}/retry` | 重试失败运行，从失败阶段继续 |
| `GET  /api/orchestrator/runs/{runId}` | 查询运行状态与每阶段状态/耗时/缓存命中 |
| `GET  /api/orchestrator/pipelines/{id}/runs` | 列出流水线全部运行 |
| `GET  /api/orchestrator/pipelines/{id}/artifacts` | 列出制品（版本/校验和/来源/输入哈希） |
| `GET  /api/orchestrator/pipelines/{id}/release` | 查询当前/上一发布指针 |
| `POST /api/orchestrator/pipelines/{id}/rollback` | 回滚（body 可空，或 `{"targetVersion":"1.0.1"}`） |
| `GET  /api/orchestrator/pipelines/{id}/cache` | 查看该流水线缓存条目 |
| `POST /api/orchestrator/pipelines/{id}/cache/corrupt?buildInputHash=...` | 诊断：损坏指定缓存 |
| `POST /api/orchestrator/pipelines/{id}/artifacts/{version}/tamper` | 诊断：篡改指定制品内容 |

触发请求体字段：

```json
{
  "idempotencyKey": "biz-key-001",
  "requestedVersion": null,
  "branch": "main",
  "sources": "baseline",
  "compatibilityTag": "builder-1",
  "stageDelayMillis": 2,
  "failStage": null,
  "failAttempt": 0
}
```

`failStage`（CHECKOUT/BUILD/TEST/PACKAGE/RELEASE）与 `failAttempt`（1 起；
`<=0` 表示每次都失败）仅用于本地确定性地模拟失败，便于验证失败停止与重试。
触发返回的 `outcome` 取值：`STARTED`（首次且执行完成）、`DUPLICATE_REPLAY`
（重复幂等键）、`REJECTED_CONFLICT`、`REJECTED_LIMIT`、`FAILED`；
`runStatus` 为运行本身状态（`PENDING/RUNNING/SUCCEEDED/FAILED`）。
冲突类错误 HTTP 状态码为 409，未找到为 404，响应体包含 `code` 与 `message`。

## 本地验证

需要 JDK 21+ 与 Maven（仓库已含 Spring Boot 依赖，可离线构建）。

```bash
# 编译
mvn -o compile

# 运行全部单元/集成测试（共 15 个，离线）
mvn -o test

# 启动服务
mvn -o spring-boot:run
```

启动后另开终端手工验证：

```bash
# 1) 首次触发
curl -s -X POST localhost:8080/api/orchestrator/pipelines/default/trigger \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"demo-1","stageDelayMillis":1}'

# 2) 重复触发（outcome=DUPLICATE_REPLAY，runId 与首次相同）
curl -s -X POST localhost:8080/api/orchestrator/pipelines/default/trigger \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"demo-1","stageDelayMillis":1}'

# 3) 模拟首次 TEST 失败，再重试
curl -s -X POST localhost:8080/api/orchestrator/pipelines/default/trigger \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"demo-fail","failStage":"TEST","failAttempt":1}'
# 用上一步返回的 runId：
curl -s -X POST localhost:8080/api/orchestrator/runs/<runId>/retry

# 4) 查询发布指针并回滚到上一版本
curl -s localhost:8080/api/orchestrator/pipelines/default/release
curl -s -X POST localhost:8080/api/orchestrator/pipelines/default/rollback \
  -H 'Content-Type: application/json' -d '{}'
```

## 测试场景覆盖

位于 `src/test/.../tests`：

- `HappyPathIntegrationTest`：成功路径、阶段状态/耗时、幂等回放可区分、
  无重复运行与制品。
- `RetryIntegrationTest`：失败即停、重试从失败点继续、成功阶段不重复计时、
  失败于 PACKAGE 前不消耗版本号。
- `ConcurrencyIntegrationTest`：同幂等键并发（唯一运行）、不同键并发
  （版本连续无跳变）、显式重复版本冲突被拒绝且不残留运行。
- `CacheIntegrationTest`：MISS/HIT、损坏检测后重建、兼容标签不兼容重建、
  LRU 容量淘汰。
- `RollbackIntegrationTest`：指针交换与校验和一致、回滚幂等、回滚到不存在
  版本/无历史版本被拒绝、失败后指针不变、制品来源可追溯。
- `LimitsIntegrationTest`：运行上限拒绝、制品保留淘汰与当前/上一版本保护。
- `ChecksumConflictUnitTest`：同版本不同校验和（区别于版本已存在/未找到）、
  内容与校验和不符拒绝、回滚到被篡改制品被拒绝且指针不变、非法版本号拒绝。
- `OrchestratorWebTest`：HTTP 入口幂等语义、404/409 错误码映射。

测试日志会打印关键输入（幂等键、版本、构建输入哈希）与判定依据
（`DECISION` / `VERIFY` / 缓存原因 / 并发结果），便于人工核对一致性。
