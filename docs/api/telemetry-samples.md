# Telemetry examples

The complete JSON example, payload requirements, and MQTT topic are documented in the root [README](../../README.md#遥测-api). The local simulator publishes the same topic with optional temperature, envelope, and peak fields omitted; its extra calculated `health_index` is ignored by the server.

## 协议 2.1.0 多模态样例

完整字段契约、主题与配置见根 [README](../../README.md#多模态异构数据处理协议-210)。以下为四种模态的慢周期遥测（`phm/telemetry/{asset_id}/{channel_id}`）与突发快照（`phm/snapshot/{asset_id}/{channel_id}`）精简载荷；`metrics` 非有限数值会被丢弃，`operating_condition` 原始键全量保留。

> **时间戳必读**：遥测 `timestamp` 与服务器时差不得超过 **±5 分钟**（快照经 MQTT 发布容忍断网补传、偏差超 1 年才拒绝；REST 直推要求 **±1 天** 内）。本文所有样例中的 `<当前毫秒时间戳>` 占位符发送前必须替换为当前 Unix 毫秒时间戳；早期文档里的固定示例值（`1732262400000`，即 2024-11-22）已无法通过新鲜度校验，直接发送会被拒绝，不要照抄。

旋转振动（`ROTATING_VIB`，设计文档 §2.1 样例，可按原样发送——平台自动映射 `feat_1x`/`env_bpfo`/`temp_bearing` 等指标名为平台特征码、派生温升并以工况转速桥接；该路径 `a_peak` 可选）：

```json
{
  "protocol_version": "2.1.0",
  "timestamp": <当前毫秒时间戳>,
  "asset_id": "PUMP_SYSTEM_01",
  "channel_id": "CH_DE_RADIAL_VIB",
  "modality_type": "ROTATING_VIB",
  "operating_condition": { "is_steady": true, "speed_rpm": 1498.5, "load_ratio": 0.85 },
  "metrics": { "v_rms": 0.485, "feat_1x": 0.452, "feat_2x": 0.118, "env_bpfo": 0.015, "a_kurtosis": 3.02, "temp_bearing": 48.2, "temp_ambient": 22.0 }
}
```

电机电流（文档别名 `ELECTRICAL_MCSA`，归一化为平台 `MCSA`；特征 `load_pct` 缺省时自动取工况 `load_pct`/`load_ratio` 桥接，下例即依赖该桥接；测点须先以 MCSA 模态创建）：

```json
{
  "protocol_version": "2.1.0",
  "timestamp": <当前毫秒时间戳>,
  "asset_id": "MOTOR_M01",
  "channel_id": "CH_STATOR_CURRENT",
  "modality_type": "ELECTRICAL_MCSA",
  "operating_condition": { "is_steady": true, "rpm": 1440, "load_pct": 75 },
  "metrics": { "mains_freq_hz": 50.0, "slip_pct": 2.5, "sideband_amp_db": -93.0, "carrier_amp_db": -33.0 }
}
```

高压断路器（`SWITCHGEAR_EVENT`，事件型动作触发）：

```json
{
  "protocol_version": "2.1.0",
  "timestamp": <当前毫秒时间戳>,
  "asset_id": "BREAKER_10KV_02",
  "channel_id": "CH_TRIP_UNIT",
  "modality_type": "SWITCHGEAR_EVENT",
  "operating_condition": { "is_steady": true, "bus_voltage_kv": 10.2, "ambient_temp": 24.5 },
  "metrics": { "trip_duration_ms": 42.5, "peak_coil_current_a": 5.42, "closing_velocity_mps": 2.15, "uhf_pd_count": 0, "contact_wear_ratio": 0.12 }
}
```

工艺调节阀（`CONTROL_VALVE_PROCESS`，可附 `cv_pct`/`fb_pct` 供回环图）：

```json
{
  "protocol_version": "2.1.0",
  "timestamp": <当前毫秒时间戳>,
  "asset_id": "FEED_VALVE_FCV101",
  "channel_id": "CH_VALVE_LOOP",
  "modality_type": "CONTROL_VALVE_PROCESS",
  "operating_condition": { "is_steady": true, "inlet_pressure_bar": 16.5, "medium_temp_c": 135.0 },
  "metrics": { "hysteresis_deadband_pct": 2.15, "travel_time_lag_ms": 420.0, "ultrasonic_leak_rms": 0.04, "friction_force_kn": 1.25, "cv_pct": 62.0, "fb_pct": 59.6 }
}
```

突发快照（断路器分闸曲线，`trigger_type` ∈ TRIP_ACTION_BURST / THRESHOLD_EXCEEDED / SCHEDULED / ON_DEMAND；`curves` 最多 8 条 × 16384 点，`health_index` 如给出须为 0–100 有限数值；同测点同采样时刻重复发布幂等，不会重复入库）：

```json
{
  "protocol_version": "2.1.0",
  "timestamp": <当前毫秒时间戳>,
  "asset_id": "BREAKER_10KV_02",
  "channel_id": "CH_TRIP_UNIT",
  "modality_type": "SWITCHGEAR_EVENT",
  "trigger_type": "TRIP_ACTION_BURST",
  "sampling_rate": 10000,
  "points_count": 1000,
  "storage_backend": "MINIO_DIRECT",
  "snapshot_url": "s3://phm-vault/20261122/BREAKER_10KV_02/1732262400000.bin",
  "preview_curve": [0.0, 0.1, 1.2, 3.5, 5.42, 4.8, 1.2, 0.1, 0.0],
  "curves": {
    "time_axis_ms": [0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0, 40.0, 45.0],
    "coil_current_a": [0.0, 0.8, 3.2, 5.42, 4.1, 2.0, 0.5, 0.1, 0.0, 0.0],
    "travel_stroke_mm": [0.0, 0.0, 0.2, 1.5, 4.8, 9.2, 14.5, 18.2, 20.0, 20.0]
  },
  "curve_units": { "coil_current_a": "A", "travel_stroke_mm": "mm" }
}
```

发布与查询示例（登录会话先经 `/api/v1/auth/csrf` + `/api/v1/auth/login` 建立；**POST 请求必须携带 CSRF 头 `X-XSRF-TOKEN`，值为 `XSRF-TOKEN` cookie / `/api/v1/auth/csrf` 返回的 token**，缺失会返回 403）：

```bash
# MQTT 发布（QoS 1，主题身份须与载荷 asset_id/channel_id 一致，比较不区分大小写）
mosquitto_pub -h localhost -t phm/telemetry/BREAKER_10KV_02/CH_TRIP_UNIT -q 1 -f breaker-telemetry.json
mosquitto_pub -h localhost -t phm/snapshot/BREAKER_10KV_02/CH_TRIP_UNIT -q 1 -f breaker-snapshot.json

# REST 直推孪生入口（载荷与 MQTT 主题相同，ADMIN/OPERATOR 角色；POST 必须带 CSRF 头）
curl -b cookies.txt -H "X-XSRF-TOKEN: <XSRF-TOKEN cookie 值>" -X POST http://localhost:8080/api/v2/phm/telemetry -H 'Content-Type: application/json' -d @breaker-telemetry.json
curl -b cookies.txt -H "X-XSRF-TOKEN: <XSRF-TOKEN cookie 值>" -X POST http://localhost:8080/api/v2/snapshots -H 'Content-Type: application/json' -d @breaker-snapshot.json

# 多模读接口（{code} 为测点 ID 或全局测点编码）
curl -b cookies.txt http://localhost:8080/api/v1/modalities
curl -b cookies.txt http://localhost:8080/api/v2/assets/tree
curl -b cookies.txt http://localhost:8080/api/v2/channels/CH_TRIP_UNIT/baseline
curl -b cookies.txt "http://localhost:8080/api/v2/telemetry/CH_TRIP_UNIT/series?step=raw"
curl -b cookies.txt http://localhost:8080/api/v2/snapshots/CH_TRIP_UNIT/latest
curl -b cookies.txt "http://localhost:8080/api/v2/channels/CH_VALVE_LOOP/hysteresis?window=PT1H"

# v2 实时推流（SSE，振动通道请用 /ws/v1/live-stream）
curl -N -b cookies.txt "http://localhost:8080/ws/v2/stream?channelCode=CH_TRIP_UNIT"
```
