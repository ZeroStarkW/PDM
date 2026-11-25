# PdM 多模态底座与 MCSA 试点实施记录（首期）

**Spec:** `docs/superpowers/specs/2026-11-23-multimodal-foundation-mcsa-design.md`

## 切片与交付物

1. **契约内核**（`telemetry/` + `modality/`）
   - 新增 `TelemetryEnvelope` / `OperatingCondition` / `ContractTelemetryRequest` / `VibrationContractAdapter` / `TelemetryContractController`（REST `/api/v2/telemetry`）。
   - 新增 `ModalityPlugin` / `ModalityPluginRegistry` / `ModalityTypes` / `BaselineSnapshot`。
   - `TelemetryValidator` 增加契约校验（版本、模态、特征有限性、必需特征、插件域校验）。
2. **振动插件抽取**（`analytics/`）
   - `RotatingVibrationPlugin` 承载既有停机判据、尖峰键、诊断与评分；`HealthIndexCalculator` 曲线抽至共享 `HealthScale`。
   - `SpikeFilter` / `AnalyticsEngine` / `TelemetryService` / `AlertService` 契约化；旧入口经适配器路径保持行为等价。
3. **V11 迁移与基线**
   - `V11__modality_and_feature_baseline.sql`：测点 `modality_type`、`pdm_baseline_feature_state`（回填旧列）、`pdm_baseline_sample_v2`。
   - `BaselineService` 泛化：振动走旧路径并双写特征状态；其他模态按特征编码独立学习；重置覆盖新表。
   - `PointEntity.modalityType`、资产 API 测点创建支持模态、`PointView`/`PointHealth` 暴露模态。
4. **MCSA 插件**（`mcsa/`）
   - `McsaPlugin` + `McsaProperties`：特征字典、稳态/负载门禁、slip 域校验、边频带比相对评分、候选 54/40 dB 绝对阈值与转子导条归因（默认关闭）。
   - `application.yml` / `.env.example` 追加 `pdm.mqtt.v2-topic` 与 `pdm.mcsa.*`（不改动既有行）。
5. **接入与存储**
   - MQTT 订阅 `pdm/v2/...` 并按主题路由；`InfluxTelemetryStore` 振动行字段不变、其他模态按特征码写字段并记录 `modality`、`raw_snapshot_ref`。
   - `CONDITION_REJECTED` 状态贯通 `DeviceHealthRollup`、前端状态标签。
6. **前端最小适配**：`Point.modalityType`、测点创建模态选择、测点详情模态标注、状态标签“工况不可评估”。
7. **测试**：`ModalityPluginRegistryTest`、`TelemetryContractValidationTest`、`VibrationContractAdapterTest`、`McsaPluginTest`、`BaselineFeatureStatisticsTest`。

## 验收

- `mvn test`：29 个测试全绿（既有 3 个 + 新增 26 个）。
- `mvn -DskipTests package`：含前端 `vue-tsc`/`vite` 构建成功。
- 兼容红线：旧载荷、模拟器、`docs/api/telemetry-samples.md` 零修改；振动 HI/告警行为与既有测试一致。

## 遗留与后续

- MCSA 趋势查询页（Influx 特征字段读取）留待试点需要时补充；当前查询端仍以振动为主。
- 54/40 dB 阈值需目标电机现场数据校准后，方可开启 `PDM_MCSA_ALERTS_ENABLED`。
- 阀门、往复机械、结构模态阶段按「数据就绪 → 特征质量验证 → 离线评估 → 受控试点 → 启用告警」推进，另立任务。
