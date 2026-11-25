# 旋转设备预测性维护平台（PdM MVP）

Java 21 / Spring Boot 4.1.2 单体应用。Vue 3 页面打包进 Spring Boot 可执行 JAR；资产、测点、基线、用户、告警和工单保存在 PostgreSQL，振动遥测与健康指数保存在 InfluxDB 3 Core。MQTT telemetry 与 HTTP REST 两种入口使用同一校验和分析流水线。

`pdm_simulator_dashboard/pdm-simulator` 是独立 Python 仿真项目，通过 MQTT 与平台集成。它默认模拟旋转泵，并在同一仪表盘控制 MCSA 电机、10kV 断路器和调节阀，数据经平台多模态预测插件处理。

## 本地启动（Windows / PowerShell）

前置条件：JDK 21、Maven 3.6.3 或更新版本、Docker Desktop、Node.js 22.12 或更新版本（Maven 构建会使用锁定的前端依赖）、PowerShell 7（初始化脚本使用 .NET 安全随机数 API）。

1. 在仓库根目录运行 `scripts/Setup-Local.ps1`。脚本生成本地 `.env` 和 InfluxDB 管理令牌文件，启动 PostgreSQL、InfluxDB 3 Core、Mosquitto，创建保留 365 天的 `pdm-ts-data` 数据库，并构建/启动单体应用。
2. 打开 [http://localhost:8080](http://localhost:8080)，使用脚本末尾显示的一次性管理员密码登录。密码保存在本地 `.env` 中，不会提交到 Git。
3. 可选：在 `pdm_simulator_dashboard/pdm-simulator` 目录运行 `uv run main.py`，然后打开 [http://localhost:5000](http://localhost:5000)。仪表盘向本地 Mosquitto 发布旋转设备双协议遥测，以及 MCSA 电机/调节阀每秒遥测和断路器动作遥测/快照。若模拟器先于 MQTT Broker 启动，请在 Broker 启动后重启模拟器。
4. 可选（多模态演示）：`.env` 保持 `PDM_DEMO_DATA=true` 时，MCSA 电机、断路器和调节阀资产及 ACTIVE 基线随演示种子开箱可用。可在模拟器仪表盘卡片中切换各设备健康/故障状态，工作台将展示 MQTT 实时样本和平台评分；断路器还可手动触发分闸。MCSA 阈值告警默认关闭，故障演示用于观察相对健康评分和趋势。

常用命令：

```powershell
docker compose ps
docker compose logs -f app
docker compose logs -f influxdb3
docker compose down
```

PostgreSQL、InfluxDB 和 MQTT 端口只绑定到本机回环地址。`mosquitto.conf` 的匿名访问只用于本地演示，不要将此配置暴露到工厂网络。

## 本地 Java 开发模式

先运行 `scripts/Setup-Local.ps1` 启动依赖服务和容器版应用。运行本地热重载前端之前，停止容器版应用以释放 8080 端口：

```powershell
docker compose stop app
Get-Content .env | ForEach-Object {
  if ($_ -match '^([^#=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process') }
}
$env:PDM_DB_URL = 'jdbc:postgresql://localhost:5432/pdm'
$env:PDM_INFLUX_URL = 'http://localhost:8181'
$env:PDM_MQTT_URL = 'tcp://localhost:1883'
mvn spring-boot:run
```

本地前端热更新：另开一个终端，在 `frontend` 运行 `npm install`，然后 `npm run dev`。Vite 将 `/api` 代理到 `localhost:8080`。正式打包由 Maven 安装 Node 22.12 / npm 10.9，执行 `npm ci`、`npm run build`，并将静态文件放入可执行 JAR。

正式 JAR 使用 InfluxDB 3 Java 客户端的 Arrow Flight / gRPC 查询，需要 JVM 参数 `--add-opens=java.base/java.nio=ALL-UNNAMED`；Docker 镜像已设置。若直接使用 `java -jar`，请先设置 `JAVA_TOOL_OPTIONS` 为该参数。

## 遥测 API

HTTP 调试入口：`POST /api/v1/telemetry`。登录会话可通过 `/api/v1/auth/login` 建立；登录前先调用 `/api/v1/auth/csrf` 获取 CSRF cookie。下面的 `timestamp` 沿用系统说明中的示例值，实际发送前必须换成当前 Unix 毫秒时间戳（与服务器相差不超过 5 分钟）。

```json
{
  "timestamp": 1773891000000,
  "factory_id": "FACT_01",
  "device_code": "PUMP_P102A",
  "point_code": "DE_RADIAL",
  "data_quality": 0,
  "telemetry": {
    "v_rms": 1.25,
    "a_peak": 8.42,
    "a_kurtosis": 3.12,
    "freq_1x_amp": 1.18,
    "freq_2x_amp": 0.45,
    "envelope_rms": 0.12,
    "envelope_bpfo_amp": 0.03,
    "rpm": 1500,
    "temperature": 48.5
  },
  "top_peaks": [
    { "frequency": 24.8, "amplitude": 1.18, "snr": 15.2 },
    { "frequency": 49.6, "amplitude": 0.45, "snr": 6.8 }
  ]
}
```

字段 `temperature`、`rpm`、`envelope_rms`、`envelope_bpfo_amp`、`top_peaks` 可选；模拟器附带频带 RMS 与 Hilbert 包络特征。未知扩展字段（例如模拟器输出的 `health_index`）会被忽略。timestamp 必须在服务器时间前后 5 分钟内。`data_quality` 为 0–3；非 0 质量码、`v_rms < 0.05 mm/s` 或已报告转速 `< 300 RPM` 的样本会保留为传感器检查/停机状态，但不参与基线或设备故障告警。

MQTT topic：`pdm/v1/{factory_id}/{device_code}/{point_code}/telemetry`，平台按 QoS 1 订阅，并校验 topic 身份与 payload 字段一致（身份比较不区分大小写）。非法报文（身份字段缺失、校验不通过等）只记录告警日志并丢弃，不会断开连接或引发重投循环。默认本地连接为 `tcp://localhost:1883`（匿名；如需认证可配置 `PDM_MQTT_USERNAME` / `PDM_MQTT_PASSWORD`，密码留空即匿名）。

## 统一遥测契约（v2）与多模态

平台内部以版本化契约承载遥测：`contract_version`（当前 2）、`modality_type`、工况信息（转速/负载率/稳态）和特征编码到有限数值的映射。旧振动入口经适配器转换为 `ROTATING_VIB` 模态，模拟器与上述旧样例无需任何修改；质量校验、去重/乱序丢弃、尖峰抑制、基线学习、评分分档与告警防抖留在共享内核，模态相关的特征定义、工况门禁与物理解释由按 `modality_type` 注册的插件提供（插件重复注册会导致启动失败，未知模态、缺失必需特征、非有限数值的样本直接拒绝，不进入评分或告警）。

新增版本化入口：

- REST：`POST /api/v2/telemetry`（与 v1 相同的会话/CSRF/角色要求）。
- MQTT：`pdm/v2/{factory_id}/{device_code}/{point_code}/telemetry`（可用 `PDM_MQTT_V2_TOPIC` 调整）。

```json
{
  "contract_version": 2,
  "modality_type": "MCSA",
  "timestamp": 1773891000000,
  "factory_id": "FACT_01",
  "device_code": "MOTOR_M01",
  "point_code": "CURRENT",
  "data_quality": 0,
  "operating_condition": { "rpm": 1440, "load_pct": 75, "steady_state": true },
  "features": {
    "mains_freq_hz": 50.0,
    "slip_pct": 2.5,
    "sideband_amp_db": -93.0,
    "carrier_amp_db": -33.0
  },
  "raw_snapshot_ref": "https://minio.internal/snapshots/m01-001.bin"
}
```

`raw_snapshot_ref` 仅为可选元数据，平台不会读取对象存储。测点需先以匹配的模态创建（`POST /api/v1/points` 传 `modality_type`，缺省 `ROTATING_VIB`），模态不匹配的遥测会被拒绝。

MCSA 试点规则：

- 必需特征：`mains_freq_hz`、`slip_pct`、`load_pct`、`sideband_amp_db`、`carrier_amp_db`（工频边频带/载波幅值，dB）；`slip_pct` 超出物理范围按无效数据拒绝。v1/v2 契约直推时 `load_pct` 须出现在特征里；协议 2.1.0 载荷可省略，缺省自动取工况 `load_pct`/`load_ratio` 桥接。
- 工况门禁：仅 `steady_state=true` 且负载率在配置区间内的样本进入评分；其余样本记录为 `CONDITION_REJECTED`（不可评估），不产生确定性结论，也不进入告警窗口。
- 评分：派生的边频带/载波幅值比相对基线（按测点+模态+特征编码独立学习）计算相对分；绝对阈值默认关闭。
- 告警门控：`PDM_MCSA_ALERTS_ENABLED=false`（默认）时不产生 MCSA 告警。现场完成健康/故障样本离线比对与试点验收后开启，届时启用候选阈值 `PDM_MCSA_SIDEBAND_WARN_DB=54` / `PDM_MCSA_SIDEBAND_DANGER_DB=40` 并输出“转子导条损伤疑似”归因。54/40 dB 为待现场校准的假设值，不是通用诊断标准。
- 基线管理沿用既有两步重置接口；按特征编码的基线状态（P50/CV/样本数/状态）可通过 `GET /api/v1/points/{id}/baseline` 的 `featureStates` 查看。

## 多模态异构数据处理（协议 2.1.0）

在统一遥测契约与模态插件底座上，平台接入两类新模态：**高压断路器机械特性**（`SWITCHGEAR_EVENT`，动作事件型，插件 `switchgear/CircuitBreakerPlugin`，bean `switchgearEventPlugin`）与**工艺调节阀**（`CONTROL_VALVE_PROCESS`，连续低频过程量，插件 `controlvalve/ControlValvePlugin`，bean `controlValvePlugin`）。共享内核新增按模态的采样节律适配：`ModalityPlugin.cadence()`（连续/事件）、`staleAfter()` 与 `BaselineLearning`（窗口覆盖/池化计数），由 `DiagnosticMicrokernel`、`SpikeFilter`、`DeviceHealthWindow` 与 `TelemetryFreshnessPolicy` 在尖峰抑制、防抖、基线学习和数据新鲜度上分别处理；对既有振动/MCSA 连续链路行为零影响。

| 模态 | 特征 | 机理评分 | 学习与新鲜度 |
| :--- | :--- | :--- | :--- |
| `SWITCHGEAR_EVENT` | 必需 `trip_duration_ms`、`peak_coil_current_a`；另学习 `closing_velocity_mps`、`contact_wear_ratio`；可选 `uhf_pd_count` 仅校验/存储 | 分闸时间超基线 1.10 倍 60 分、超 1.20 倍 25 分（硬限击穿直接 DANGER 且防抖直通）；线圈电流峰值低于基线 0.80 倍 40 分 | 动作事件永远视为稳态；池化基线 ≥200 样本即可锁定（样本保留 P365D）；数据新鲜度 P30D；尖峰抑制直通，防抖窗口不因时间断档清空 |
| `CONTROL_VALVE_PROCESS` | 必需 `hysteresis_deadband_pct`、`ultrasonic_leak_rms`；另学习 `travel_time_lag_ms`、`friction_force_kn`；可选 `cv_pct`/`fb_pct` 仅存储/查询（前端回环图） | 死区恶化倍率超基线 5.0 倍 20 分、超 2.5 倍 40 分；超声泄漏 >0.5 RMS 30 分（硬限）、>0.2 65 分 | 仅 `steady_state=true` 样本评分与学习；基线学习窗口按模态独立 PT1H |

两档倍率/泄漏阈值均为默认设计值（同 MCSA，需现场校准），可按测点经 `metadata_config` 逐通道覆盖（如 `duration_attention_ratio`、`deadband_danger_ratio`）；覆盖值按与全局属性相同的约束做范围校验（如击穿时长 danger 倍率必须 > attention 且 > 1），越界创建/编辑测点时返回 400。硬限击穿（如阀门超声泄漏 > 0.5 RMS）直接报 DANGER 并生成 HIGH 优先级工单。η_dyn 劣化速率按模态节律设拟合门槛：事件型 ≥8 个动作且跨越 ≥1/12 窗口，连续型 ≥24 个样本（5 分钟抽稀）且跨越 ≥1/2 窗口，另有 ≥0.15 倍率区间门槛，噪声通道不再误报 DANGER。遥测特征键须匹配 `^[a-z][a-z0-9_]{0,63}$` 且不得占用平台保留名（`time`、`factory_id`、`device_code`、`point_code`、`data_quality`、`status_code`、`status`、`modality`、`health_index`、`raw_snapshot_ref`、`top_peaks_json`），违规样本返回 400 并点名该键。

### 协议 2.1.0 接入

新增两条 MQTT 订阅主题，报文带 `protocol_version`（当前 2.1.0）统一信封；MQTT 路由顺序为 `pdm/v2` → `phm/telemetry` → `phm/snapshot` → `devices/…/raw_waveform` → `devices`（协议 2.0.0）→ 旧版 `pdm/v1`（路由前缀由配置的主题派生）。载荷缺失 `asset_id`/`channel_id` 等身份字段、或校验不通过时只记录告警并丢弃该报文（避免毒消息断连重投）；topic 身份校验不区分大小写。Broker 认证可用 `PDM_MQTT_USERNAME` / `PDM_MQTT_PASSWORD`（默认留空即匿名）。下文样例中的 `timestamp` 均为文档示例值，实际发送前必须替换为当前 Unix 毫秒时间戳（遥测与服务器相差 ±5 分钟内；固定示例值如 `1732262400000` 会被新鲜度校验拒绝，完整说明见 `docs/api/telemetry-samples.md`）：

- **慢周期多态特征遥测** `phm/telemetry/{asset_id}/{channel_id}`（`PDM_MQTT_PHM21_TOPIC`）：`asset_id` 映射设备编码、`channel_id` 映射测点编码（按编码全局定位，工厂经资产反查）。`modality_type` 支持 `ROTATING_VIB` / `SWITCHGEAR_EVENT` / `CONTROL_VALVE_PROCESS` 及文档别名 `ELECTRICAL_MCSA`（归一化为平台 `MCSA`）；`operating_condition` 原始键全量保留并派生 `rpm`/`load_pct`/`steady_state`（兼容 `speed_rpm`/`load_ratio`/`is_steady` 别名）；`metrics` 中非有限数值静默丢弃。设计文档 §2.1 的旋转振动指标名可按原样发送：平台在平台码未给出时自动映射 `feat_1x`/`feat_2x`/`feat_3x`/`feat_sub`/`env_rms`/`env_bpfo`/`env_bpfi`/`temp_bearing`/`temp_ambient` → 平台特征码并派生温升 `temp_rise`；指标未给转速时以工况 `speed_rpm`/`rpm` 桥接为特征 `rpm`。该路径 `a_peak` 可选（v1/v2 契约仍必填），缺省振动样本入库时以哨兵值 −1.0 占位、查询侧读回为 null。`ROTATING_VIB` 载荷 `is_steady=false` 按工况门禁拒绝（`CONDITION_REJECTED`）。断路器遥测样例：

```json
{
  "protocol_version": "2.1.0",
  "timestamp": 1732262400000,
  "asset_id": "BREAKER_10KV_02",
  "channel_id": "CH_TRIP_UNIT",
  "modality_type": "SWITCHGEAR_EVENT",
  "operating_condition": { "is_steady": true, "bus_voltage_kv": 10.2, "ambient_temp": 24.5 },
  "metrics": {
    "trip_duration_ms": 42.5,
    "peak_coil_current_a": 5.42,
    "closing_velocity_mps": 2.15,
    "uhf_pd_count": 0,
    "contact_wear_ratio": 0.12
  }
}
```

- **突发曲线快照** `phm/snapshot/{asset_id}/{channel_id}`（`PDM_MQTT_PHM_SNAPSHOT_TOPIC`）：`trigger_type` 白名单 `TRIP_ACTION_BURST` / `THRESHOLD_EXCEEDED` / `SCHEDULED` / `ON_DEMAND`；`preview_curve` 上限同 `pdm.waveform.max-preview-points`（默认 16384 点），`curves`/`curve_units`/`metrics` 以 JSONB 入库（最多 8 条 × 每条 16384 点），`health_index` 须为 0–100 有限数值；`preview_curve` 归档本地卷并以 `file://` 引用（`WaveformStorage` 扩展点），边缘侧 `snapshot_url` 单独保留。MQTT 时间戳容忍断网补传（偏差超 1 年才拒绝），REST 直推要求 ±1 天内；同测点同 `sampled_at` 的重复快照幂等重放不重复入库（迁移 `V16__channel_snapshot_unique.sql` 去重 + 唯一约束）；`TRIP_ACTION_BURST`/`THRESHOLD_EXCEEDED` 快照会回链最近一条未关闭告警的 `raw_snapshot_url`。`ROTATING_VIB`/`MCSA` 测点快照返回 400（振动类走 `devices/…/raw_waveform` 波形通道）。断路器快照样例：

```json
{
  "protocol_version": "2.1.0",
  "timestamp": 1732262400000,
  "asset_id": "BREAKER_10KV_02",
  "channel_id": "CH_TRIP_UNIT",
  "modality_type": "SWITCHGEAR_EVENT",
  "trigger_type": "TRIP_ACTION_BURST",
  "sampling_rate": 10000,
  "points_count": 1000,
  "storage_backend": "MINIO_DIRECT",
  "snapshot_url": "s3://phm-vault/20261122/BREAKER_10KV_02/1732262400000.bin",
  "preview_curve": [0.0, 0.1, 1.2, 3.5, 5.42, 4.8, 1.2, 0.1, 0.0]
}
```

调节阀遥测仅在 `metrics`/工况键上不同（`hysteresis_deadband_pct`、`travel_time_lag_ms`、`ultrasonic_leak_rms`、`friction_force_kn`，可附 `cv_pct`/`fb_pct`；工况含 `inlet_pressure_bar`、`medium_temp_c` 等，原始键全量保留），完整样例见 `docs/api/telemetry-samples.md`。

无 MQTT 条件时可用 REST 直推孪生入口：`POST /api/v2/phm/telemetry`（202，与主题共用载荷与服务路径）与 `POST /api/v2/snapshots`（201），均要求 ADMIN/OPERATOR 角色。

### 多模读接口

`{code}` 均可为测点 ID（正整数）或全局唯一测点编码；跨工厂重码返回 409。

| 接口 | 说明 |
| :--- | :--- |
| `GET /api/v1/modalities` | 模态目录：插件标识、采样节律、必需/基线/时序特征、主特征（即告警触发特征）与趋势特征、机理参数元数据（可按通道覆盖的键与默认值）、告警开关、新鲜度与基线学习策略 |
| `GET /api/v2/assets/tree` | 与 v1 同形的多模态资产树；测点节点增加 `pluginIdentifier`/`samplingRate`/`engineeringUnit`/`metadataConfig`/`cadence` |
| `GET /api/v2/channels/{code}/baseline` | 通道基线矩阵：`featureStates`（featureKey/baselineP50/cvValue/sampleCount/status/lockedAt）+ `learningWindowSeconds`/`pooled`；学习期可能含 `status=PROVISIONAL` 的现算预览行（窗口中位数/CV/样本数 ≥ 8，`lockedAt` 为 null），锁定前工作台即可观察收敛 |
| `GET /api/v2/telemetry/{code}/series` | 跨模态时序：`start_ts`/`end_ts` 毫秒（须在 1971-01-01 之后且不超过服务器时间 + 1 天），`step` ∈ `1m`/`5m`/`15m`/`1h`/`6h`/`raw`（raw 上限 2000 行，聚合上限 5000 行，超出保留最新并置 `truncated=true`，行按时间升序）；行键 `timestamp`(epoch ms)/`health_index`/`status`/`data_quality`/`metrics` |
| `GET /api/v2/snapshots/{code}/latest` | 最新突发快照：`previewCurve`/`curves`/`curveUnits`/`metrics`/`annotations`。断路器标注 `tripStartMs`/`contactSeparationMs`/`brakingMs`/`totalTimeMs`/`peakCurrentA`/`fullStrokeMm`/`maxVelocityMps`；调节阀回环量化 `deadbandEstimatePct`/`loopAreaPct2`/`hysteresisPct`/`maxDeviationPct`/`reversalCount`；振动通道回退最近波形快照；无快照 404 |
| `GET /api/v2/channels/{code}/hysteresis?window=PT1H` | 调节阀 CV/FB 迟滞回环；非阀模态返回 200 + `status=UNSUPPORTED_MODALITY`，无配对样本 `NO_DATA`（此时 `from`/`to` 为 null），窗口超 P7D 按 P7D 截断；`from`/`to` 报告首/末配对样本的 epoch ms（非请求窗口），载荷含 `truncated` 布尔与 `limit`（20000 行 raw 上限） |

实时推流：`GET /ws/v2/stream?channelCode={测点ID或编码}`（SSE），命名事件 `metrics_frame`/`stroke_event_frame`/`valve_loop_frame`，载荷为 snake_case 键 + `frame_type`（`annotations` 内标注键沿用 camelCase，与快照接口一致）。每个被接受的非振动样本都会产生一条 `metrics_frame`：完成评分的帧带 `health_index`/`alarm_level`；学习期/工况门禁/缓冲/尖峰抑制帧的 `health_index` 与 `alarm_level` 为 null，可选 `status` ∈ `BASELINE_LEARNING` / `BASELINE_DIVERGENCE` / `CONDITION_REJECTED` / `STOPPED` / `SPIKE_SUPPRESSED` / `ANALYSIS_BUFFERING`；劣质数据样本不产生帧；帧在入库事务提交后发布；`annotations` 恒为 `{}` 不为 null。振动通道返回 409 并继续使用 `/ws/v1/live-stream`。测点管理 API（创建/编辑）同步扩展 `samplingRate`/`engineeringUnit`/`metadataConfig`/`pluginIdentifier` 字段（`pluginIdentifier` 仅校验不落库，按模态解析；模态名自动归一化，接受 `ELECTRICAL_MCSA` 与小写输入）。通道模型迁移见 `V13__multimodal_channel.sql`（模态约束放宽为 `^[A-Z][A-Z0-9_]{0,31}$` 正则 + 三列）与 `V14__channel_snapshot.sql`（`pdm_channel_snapshot` 表）；后续 `V15__nullable_baseline_a_peak.sql`（基线样本 `a_peak` 可空）与 `V16__channel_snapshot_unique.sql`（快照去重 + 唯一约束）。资产树/测点列表对个别未注册模态的测点容错（`pluginIdentifier`/`cadence` 置 null，不再 400）。

### 多模态专家工作站

前端 `frontend/src/views/WorkbenchView.vue` 重构为动态组件分发器（`components/workbenches/`）：`RotatingVibrationWorkbench`（原三联动频谱逻辑平移，行为不变）、`SwitchgearStrokeWorkbench`（线圈电流×行程双 Y 轴 + 起动/分离/制动标注 + 总时差色带）、`ControlValveHysteresisWorkbench`（CV-FB 回环 + 45° 参考线 + 死区/面积/迟滞量化框）、`GenericMetricsWorkbench`（MCSA/未知模态兜底，不抛错）。统一外框含 HI/基线摘要条、跨模态劣化趋势、本设备告警侧栏与 v2 SSE；测点详情、总览、设备管理与导航同步多模化，新模态测点创建表单按模态目录渲染采样率、工程单位与机理参数。

### 验收与测试

`mvn -o -Dskip.installnodenpm -Dskip.npm test` 当前 313 个测试全绿。多模验收用例（设计文档 §7）在 `multimodal/MultimodalAcceptanceTest`：TC-MM-01 包络解调 140Hz 点蚀扣至 55 分（附无冲击对照信号变体）；TC-MM-02 分闸 52ms/40ms 硬限直通报“分闸动作迟缓，机械传动卡涩”（附 M-of-N 跨日防抖变体）；TC-MM-03 阀门死区 k=3.5 扣至 40 分“填料压盖过紧或阀杆结垢卡阻”；TC-MM-04 205 条含 5 条 200ms 飞刺的学习池经 MAD 剔除后 P50 误差 ≤1%、CV ≤0.10 锁定 ACTIVE（MAD 边界值按 4.4/4.5 单测覆盖）。另有插件边界、内核调度、快照/回环解算、流发布与契约序列化等单测覆盖。

配置键汇总（详见 `.env.example`）：

| 环境变量 | application.yml 键 | 默认值 |
| :--- | :--- | :--- |
| `PDM_MQTT_PHM21_TOPIC` | `pdm.mqtt.phm21-topic` | `phm/telemetry/+/+` |
| `PDM_MQTT_PHM_SNAPSHOT_TOPIC` | `pdm.mqtt.phm-snapshot-topic` | `phm/snapshot/+/+` |
| `PDM_SWITCHGEAR_ALERTS_ENABLED` | `pdm.switchgear.alerts-enabled` | `true` |
| `PDM_SWITCHGEAR_DURATION_ATTENTION_RATIO` | `pdm.switchgear.duration-attention-ratio` | `1.10` |
| `PDM_SWITCHGEAR_DURATION_DANGER_RATIO` | `pdm.switchgear.duration-danger-ratio` | `1.20` |
| `PDM_SWITCHGEAR_CURRENT_ATTENTION_RATIO` | `pdm.switchgear.current-attention-ratio` | `0.80` |
| `PDM_SWITCHGEAR_STALE_AFTER` | `pdm.switchgear.stale-after` | `P30D` |
| `PDM_SWITCHGEAR_SAMPLE_RETENTION` | `pdm.switchgear.sample-retention` | `P365D` |
| `PDM_SWITCHGEAR_DEGRADATION_WINDOW` | `pdm.switchgear.degradation-window` | `P30D` |
| `PDM_SWITCHGEAR_MAX_PAST_DELAY` | `pdm.switchgear.max-past-delay` | `P30D` |
| `PDM_CONTROL_VALVE_ALERTS_ENABLED` | `pdm.control-valve.alerts-enabled` | `true` |
| `PDM_CONTROL_VALVE_DEADBAND_ATTENTION_RATIO` | `pdm.control-valve.deadband-attention-ratio` | `2.5` |
| `PDM_CONTROL_VALVE_DEADBAND_DANGER_RATIO` | `pdm.control-valve.deadband-danger-ratio` | `5.0` |
| `PDM_CONTROL_VALVE_LEAK_ATTENTION_RMS` | `pdm.control-valve.leak-attention-rms` | `0.2` |
| `PDM_CONTROL_VALVE_LEAK_DANGER_RMS` | `pdm.control-valve.leak-danger-rms` | `0.5` |
| `PDM_CONTROL_VALVE_LEARNING_WINDOW` | `pdm.control-valve.learning-window` | `PT1H` |

## MVP 规则与 API

- 初始测点基线状态为 LEARNING。基线使用配置学习窗口内至少 200 个合格样本；按 `data_quality=0`、`v_rms >= 0.05` 和（若有转速）额定转速 ±5% 过滤运行样本。对 1X、2X、RMS、可选 BPFO 包络和温度分别执行 MAD 清洗，以 P50 为基线；各指标 CV 均不高于 0.15 才锁定，超出则标记 DIVERGED 并继续学习。锁定基线不会自动滑动。
- HI 取 1X、2X、BPFO 包络和温度相对基线分，以及 `v_rms` 绝对振动限值分中的最低值。设备 HI 以在线有效测点中最低的 HI 作为保守代表分，并显示测点覆盖和局部证据；不会用均值掩盖单个高风险位置。HI 50–<70 显示关注，30–<50 显示警告，<30 显示危险；最近 5 次有效运行样本中至少 4 次 HI <50 后，建立设备级告警和工单，同设备/级别/诊断规则 4 小时内合并。传感器异常、停机和过期数据作为测点数据状态显示，不直接转为设备故障。
- 管理员：资产/测点维护、设备级基线重置、误报告警抑制；运维人员：告警确认、工单分派和闭环；查看者：只读。设备基线重置支持省略测点 ID 以作用于设备下全部可重置基线，也可指定受影响测点。
- 维护计划按设备配置周期和下次到期时间，系统生成待办维护任务并保留完成/取消历史。任务记录现场发现和已采取措施；它与告警触发的维修工单分别管理。
- 趋势估算使用最近最多 30 天的小时 HI，通过线性拟合估算达到 HI 50 的时间。至少需要 24 个有效小时观测且跨越 24 小时；数据不足、HI 已到阈值或趋势未下降时分别返回说明状态。输出的斜率、拟合优度和置信等级描述趋势证据，不代表经过故障标签训练或现场验证的剩余寿命预测。
- 告警 Webhook 进入 PostgreSQL 持久化队列，失败后按退避间隔重试，最多 8 次；失败事件可由管理员或运维人员手动重试。告警在配置的确认时限内未被确认时产生一次升级通知。发送成功不等于告警已确认。当前通知仍使用通用 JSON Webhook，接收方应依据稳定的 `Idempotency-Key` 去重。
- API：`/api/v1/dashboard/overview` 返回设备级健康汇总及各测点证据；另有 `/api/v1/factories`、`/api/v1/devices`、`/api/v1/devices/{id}/failure-prediction`、`/api/v1/devices/{id}/baseline/reset-request`、`/api/v1/devices/{id}/baseline/confirm-reset`、`/api/v1/points`、`/api/v1/points/{id}/telemetry?from=&to=&interval=`、`/api/v1/alerts`（告警视图含 `modalityType` 与 `triggerFeatureCode` 触发特征）、`/api/v1/work-orders`（同一工单控制器另挂 `/api/v1/workorders` 与 `/api/v2/workorders` 别名，工单创建/派单/闭环路由一致）、`/api/v1/maintenance/plans`、`/api/v1/maintenance/tasks`、`/api/v1/points/{id}/baseline`（只读）。趋势支持 1m、5m、15m、1h、6h 聚合间隔。

## 配置和数据

- 数据库连接和凭据来自环境变量或本地 `.env`，仓库只包含 `.env.example`。
- 可通过 `PDM_ADMIN_USERNAME` / `PDM_ADMIN_PASSWORD` 引导创建第一个管理员；若两者都留空，不创建默认账号。
- 基线学习窗口可通过 `PDM_BASELINE_LEARNING_DURATION` 配置，使用 ISO-8601 时长格式，默认 `PT6M`，为实际略低于 1 Hz 的采样频率留出窗口余量；例如设为 `PT10M` 使用最近 10 分钟内的有效样本，仍至少需要 200 个样本且满足稳定性条件。修改后需重启应用。
- `PDM_ALERT_WEBHOOK_ENABLED=true` 且 `PDM_ALERT_WEBHOOK_URL` 非空时，持久化队列会通过通用 JSON Webhook 投递通知；默认关闭。关闭时待发送事件会保留且不消耗重试次数。
- 通知配置：`PDM_NOTIFICATION_POLL_INTERVAL` 默认 `PT5S`、`PDM_NOTIFICATION_ACKNOWLEDGEMENT_SLA` 默认 `PT30M`、`PDM_NOTIFICATION_INITIAL_BACKOFF` 默认 `PT30S`、`PDM_NOTIFICATION_MAX_BACKOFF` 默认 `PT1H`、`PDM_NOTIFICATION_MAX_ATTEMPTS` 默认 8（最大 8）。维护计划任务轮询由 `PDM_MAINTENANCE_POLL_INTERVAL` 配置，默认 `PT1M`。
- 可通过 `PDM_MQTT_ENABLED=false` 禁用 MQTT consumer；REST 接口仍可用。
- `PDM_DEMO_DATA=true` 时创建 `FACT_01` 下的旋转泵、MCSA 电机 `MOTOR_M01 / CH_STATOR_CURRENT`、断路器 `BREAKER_10KV_02 / CH_TRIP_UNIT` 和调节阀 `FEED_VALVE_FCV101 / CH_VALVE_LOOP`。三个多模态设备分别归类为 `ELECTRIC_MOTOR`（电动机）、`CIRCUIT_BREAKER`（高压断路器）和 `CONTROL_VALVE`（调节阀）；遥测仍按测点模态路由。MCSA、断路器和调节阀分别带 ACTIVE 特征基线；断路器/阀门另带合成工作台快照（不会回填 InfluxDB 历史）。使用模拟器仪表盘生成实时数据并观察各工作台评分。`PDM_MCSA_ALERTS_ENABLED` 默认保持关闭；54/40 dB 为待现场校准的候选阈值。`compose.yaml`/`application.yml` 默认关闭演示数据，本仓库 `.env.example` 打开仅为本地演示便利。仍可单独运行 `scripts/Publish-MultimodalSamples.ps1` 作为断路器/调节阀的手工 MQTT 发布工具。
- 应用启动时会为已存在且尚无规则的多模态设备类型补齐初始主导因素规则：MCSA 边频带间距 54/40 dB；断路器分闸时间倍率 1.10 与线圈电流倍率 0.80；调节阀死区倍率 2.5/5.0 与泄漏 RMS 0.2。此初始化不依赖 `PDM_DEMO_DATA`，已有规则（含禁用规则）保持原样。阈值均是演示候选值，需现场校准；断路器分闸时间倍率 >1.20 和调节阀泄漏 RMS >0.5 仍由插件硬限诊断优先。MCSA 确定性告警仍默认关闭。
- InfluxDB 3 Core 在创建数据库时设置 365 天保留期。该值在 Core 中创建后不可修改；如需调整，创建新数据库并迁移后端 `PDM_INFLUX_DATABASE`。

## MVP 范围

平台不运行 FFT，也不训练机器学习模型；常规遥测以边缘计算后的特征值为主，但协议 2.1.0 起同时接收边缘主动上报的突发原始波形快照（`devices/…/raw_waveform` 与 `phm/snapshot/…`，预览曲线入库、完整数组归档本地卷），用于专家工作站回放与标注解算。随附模拟器演示频带能量与包络提取；生产网关仍需依据现场采样率和轴承几何参数校准。HI 阈值趋势估算尚未使用带标签故障历史验证，不能作为保证的失效日期或独立检修决策依据。当前健康度和趋势面向演示规模；未完成生产级 TLS 双向证书、Webhook 厂商卡片、CMMS/EAM 集成、压测和高可用部署。

## PHM 专家级升级（协议 2.0.0）

依据《工业设备预测性维护与健康管理系统（PHM）全栈系统设计说明书》完成以下升级，原有 v1/v2 遥测契约与既有数据保持完全兼容：

1. **协议 2.0.0 慢遥测**：新主题 `devices/{asset_id}/{point_id}/telemetry`，报文含 `operating_condition{rpm, load_current, is_steady}` 与文档 §2.1 全量 metrics（crest_factor、feat_3x、feat_sub、env_bpfo、env_bpfi、sei_index、temp_bearing/temp_ambient 等）。模拟器双通道发布（旧 v1 + 协议 2.0.0）。
2. **原始波形快照**：主题 `devices/{asset_id}/{point_id}/raw_waveform`（原始波形仅经 MQTT 接入，REST 直推路由未开放）；三类触发（越限 THRESHOLD_EXCEEDED / 定时 SCHEDULED / 专家召唤 ON_DEMAND）。预览波形入 PostgreSQL，原始数组归档本地卷（`WaveformStorage` 接口可扩展 MinIO/S3）。
3. **轴承几何库**：`pdm_bearing_library` 字典（BPFO/BPFI/BSF/FTF 系数）+ 测点绑定；`GET /api/v1/bearings/models?keyword=6208` 模糊检索；设备页创建测点时可搜索并回显理论系数。
4. **健康内核**：dk/dt 劣化速率拟合与雪崩抑制乘子 η_dyn=exp(−2.5(β₁−0.10))；文档版 ISO 10816 曲线；温升（轴承−环境）补偿评分；BPFI 包络评分；综合 HI = min(1X, 2X, BPFO, BPFI, ISO, Temp) × η_dyn；确定性根因阶梯（雪崩 → 1X 不平衡 → 2X 不对中 → BPFO 外圈 → BPFI 内圈 → ISO → 温升）写入告警 `defect_detail`。
5. **API 面**：`GET /api/v1/assets/tree`（健康标签资产树）、`GET /api/v1/telemetry/{pointId}/features`、`GET /api/v1/diagnostics/{pointId}/waveform`、`POST /api/v1/diagnostics/{pointId}/capture`（召唤）、`POST /api/v1/workorders`（一键派单）、SSE 实时频谱流 `GET /ws/v1/live-stream?pointId=`（2Hz SPECTRUM_FRAME，含 1X~6X 谐波光标与故障线 SNR）。
6. **专家频谱诊断工作站**（前端 `/workbench`）：时域示波器（滚轮缩放 + 峰峰值卡尺 Δt→频率）、FFT 频谱仪（线性/dB 切换 + 谐波光标族）、希尔伯特包络谱（BPFO/BPFI/BSF 故障线 + SNR≥5 高亮确诊提示），三视图由同一帧数据驱动；告警页新增主因/机理佐证/劣化速率诊断卡片与一键派单。
7. **验收测试（文档 §9 DoD）**：`mvn test` 覆盖 TC-01（Band RMS 抗转频漂移 ≤1.5%）、TC-02（希尔伯特包络捕获 140Hz 点蚀并扣至 60 分）、TC-03（MAD 剔除 5 条 10× 飞刺，P50 误差 ≤1%）、TC-04（k:1.05→1.3/4h → dk/dt≈1.5/24h，HI 强制压至 <45）。模拟器侧 `unittest discover` 验证包络解调与三类报文契约。

新增配置项见 `.env.example`（`PDM_MQTT_PHM_TOPIC`、`PDM_HEALTH_DEGRADATION_WINDOW`、`PDM_WAVEFORM_*`）。波形归档目录在 compose 中挂载为 `waveform-data` 卷。
