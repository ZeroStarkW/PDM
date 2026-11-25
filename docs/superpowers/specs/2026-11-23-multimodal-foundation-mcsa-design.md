# PdM 多模态底座与 MCSA 试点设计（首期）

## Intent

将现有旋转振动链路迁入「共享分析内核 + 模态插件」架构，新增版本化统一遥测契约与 MCSA（电机电流特征分析）试点插件，并把基线状态迁移为按测点 + 模态 + 特征编码管理。旧振动入口、模拟器与现有行为保持等价。

## Current State

- 单模态振动 MVP：REST `/api/v1/telemetry` 与 MQTT `pdm/v1/...` 共享 校验 → 去重 → 质量门禁 → 尖峰抑制 → 基线学习 → 规则诊断 → HI 评分 → 设备级告警防抖 的固定链路。
- 振动特征固定在 `TelemetryRequest` 契约中；基线状态冻结在 `pdm_measurement_point` 旧列；无模态、插件或契约概念。
- Flyway 已用至 V10；测试为纯单元测试（无 Testcontainers）。

## Goals

1. 统一接入契约（contract_version=2）：带模态、工况与特征编码映射；旧入口适配为 `ROTATING_VIB`；新 REST `/api/v2/telemetry` 与 MQTT `pdm/v2/...` 入口接收契约。
2. 模态插件注册表：质量校验、去重、工况分发、尖峰抑制、基线学习、评分分档、告警防抖留在共享内核；每个 `modality_type` 唯一注册（重复注册启动失败）；未知模态、缺失必需特征、非有限数值拒绝进入评分或告警。
3. 基线按特征编码管理：新表 `pdm_baseline_feature_state`（P50/CV/样本数/状态）与 `pdm_baseline_sample_v2`（新模态样本）；振动保持旧表旧列权威并双写，迁移期保留旧数据读取能力；重置路径覆盖新表。
4. MCSA 试点：工频/转差/负载门禁、边频带基线相对评分、候选绝对阈值默认关闭（`pdm.mcsa.alerts-enabled=false`），验收后启用并做转子导条归因；`raw_snapshot_ref` 仅作元数据。

## Non-goals

- 不实现阀门、往复机械、结构模态插件（后续阶段按数据就绪再拆任务）。
- 平台不读取原始电流波形、不运行 FFT、不做对象存储访问。
- 54/40 dB 边频带阈值仅为候选设计假设，不作为通用诊断标准；MCSA 未验收不启用告警。

## Behavior

- 契约校验：版本、模态、公共约束（时间戳偏移、质量码）、特征有限性、插件必需特征与特征域校验（如 slip 物理范围）。
- 工况门禁：振动停机判据不变；MCSA 仅稳态且负载在配置区间内可评分，否则 `CONDITION_REJECTED`（状态码 6），重置防抖、不评分不定论。
- 评分：共享 `HealthScale`（相对曲线 + 70/50/30 分档）；振动插件复用既有 ISO 10816 绝对分与规则诊断；MCSA 用边频带/载波幅值比（10^((sideband−carrier)/20)）做相对分，绝对分默认 100（不参与），开启后按候选 dB 阈值分档并归因。
- 基线：共享 MAD/P50/CV 内核；≥200 样本且覆盖学习窗口；振动锁定时旧列与新表双写，其他模态仅写新表；重置同时清理。
- 告警：`plugin.alertsEnabled()` 门控；触发/基线幅值由插件提供（振动=1X 幅值，MCSA=边频带比）；去重合并键不变。

## Constraints

- 不回滚或覆盖工作区既有未提交修改；`application.yml` 本地凭据行不动，仅追加键。
- 振动链路为兼容红线：旧 REST/MQTT 载荷、`docs/api/telemetry-samples.md`、模拟器零修改。
- `ddl-auto: validate` 要求迁移与实体严格一致；迁移版本使用 V11。

## Acceptance

- 单元测试覆盖：插件唯一注册、未知模态/缺特征/非有限值拒绝、振动适配与停机/尖峰等价、MCSA 合成样本门禁与评分边界、特征独立学习与锁定。
- `mvn test` 全绿；`mvn -DskipTests package`（含前端构建）成功。
- 振动 HI 与告警行为与既有测试一致（`HealthIndexCalculatorTest` 等不修改断言）。
