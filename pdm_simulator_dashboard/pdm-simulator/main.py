# /// script
# requires-python = ">=3.10"
# dependencies = [
#     "flask>=3.0.0",
#     "numpy>=1.24.0",
#     "scipy>=1.11.0",
#     "paho-mqtt>=2.0.0",
# ]
# ///

import json
import math
import random
import threading
import time
from flask import Flask, jsonify, render_template_string, request
import numpy as np
from signal_features import BPFI_FACTOR, BPFO_FACTOR, extract_band_energy_rms, extract_envelope_features
from multimodal_simulation import (BREAKER_ASSET, BREAKER_CHANNEL, BREAKER_MODES,
                                   MOTOR_ASSET, MOTOR_CHANNEL, MOTOR_MODES,
                                   VALVE_ASSET, VALVE_CHANNEL, VALVE_MODES,
                                   build_breaker_sample, build_motor_payload, build_valve_payload)
import paho.mqtt.client as mqtt

# ======================= 1. 全局配置与状态机 =======================
app = Flask(__name__)

CONFIG = {
    "mqtt_broker": "127.0.0.1",
    "mqtt_port": 1883,
    "legacy_topic_prefix": "pdm/v1/FACT_01/PUMP_P102A",   # 兼容旧 v1 契约主题前缀（仅在 protocol_mode == "v1" 时启用）
    "asset_id": "PUMP_P102A",                             # 协议 2.0.0：devices/{asset_id}/{point_id}/telemetry
    "protocol_mode": "2.0",                               # 默认单发 PHM 2.0.0 慢遥测（杜绝同戳双发互锁丢弃扩展特征）
    "enable_mqtt": True,
    "mqtt_point_codes": ["DE_RADIAL"],                    # MQTT 慢遥测发布白名单；空列表或 None 表示发布全部 SENSOR_POINTS（本地仪表盘始终展示全部 3 个测点视图）
    "sample_rate": 8192,                                  # 提升采样率以支撑 1~2.5kHz 包络解调带
    "sample_points": 4096,
    "ambient_temp": 24.0,     # 环境温度 (℃)
    "load_current": 45.2,     # 额定电流 (A)，随故障模式浮动
    "scheduled_waveform_interval": 3600.0,  # 定时巡检波形（秒），现场建议每日 1~2 次
    "threshold_waveform_cooldown": 60.0,    # 越限触发限流（秒）
}

# 每个测点独立生成振动数据并发布到对应的 MQTT topic。
SENSOR_POINTS = [
    {"point_code": "DE_RADIAL", "point_name": "驱动端径向", "amplitude_scale": 1.0, "base_1x_amp": 0.45},
    {"point_code": "NDE_RADIAL", "point_name": "非驱动端径向", "amplitude_scale": 0.85, "base_1x_amp": 0.3825},
    {"point_code": "DE_AXIAL", "point_name": "驱动端轴向", "amplitude_scale": 0.65, "base_1x_amp": 0.2925},
]

device_state = {
    "mode": "HEALTHY",
    "target_rpm": 1500.0,
    "current_rpm": 1500.0,
    "current_temp": 48.0,     # 实时表面温度 (℃)
    "target_temp": 48.0,      # 目标热平衡温度 (℃)
    "base_1x_amp": 0.45,
    "base_temp": 48.0,        # 基准健康温度 (℃)
    "spike_injected": False,
    "is_running": True,
    "capture_requested": set(),   # 专家召唤 (ON_DEMAND) 待抓取测点
    "last_threshold_waveform": 0.0,
    "last_scheduled_waveform": 0.0,
}

latest_view_data = {
    "metrics": {},
    "mode": "HEALTHY",
    "current_rpm": 1500.0,
    "current_temp": 48.0,
    "had_spike": False,
    "time_waveform": [],
    "fft_spectrum": {"freqs": [], "amps": []},
    "sensor_points": [],
    "multimodal_devices": {},
}

MULTIMODAL_DEVICES = {
    MOTOR_ASSET: {"mode": "HEALTHY", "cycle": 0},
    BREAKER_ASSET: {"mode": "HEALTHY", "cycle": 0, "last_event": 0.0, "trigger_now": False},
    VALVE_ASSET: {"mode": "HEALTHY", "cycle": 0},
}
MULTIMODAL_MODES = {
    MOTOR_ASSET: MOTOR_MODES,
    BREAKER_ASSET: BREAKER_MODES,
    VALVE_ASSET: VALVE_MODES,
}
multimodal_lock = threading.Lock()

# ======================= 2. MQTT 客户端（订阅专家召唤指令） =======================
mqtt_client = None


def publish_multimodal_payload(topic, payload):
    if not mqtt_client:
        return False
    try:
        result = mqtt_client.publish(topic, json.dumps(payload, allow_nan=False), qos=1)
        return result.rc == mqtt.MQTT_ERR_SUCCESS
    except (TypeError, ValueError, OSError) as error:
        print(f"[!] 多模态 MQTT 发布失败 {topic}: {error}")
        return False


def update_multimodal_simulation():
    """合成并发布 MCSA/断路器/调节阀数据，供看板显示最近一帧。"""
    now_ms = int(time.time() * 1000)
    now_seconds = time.monotonic()
    views = {}
    with multimodal_lock:
        for asset_id in (MOTOR_ASSET, VALVE_ASSET):
            state = MULTIMODAL_DEVICES[asset_id]
            mode = state["mode"]
            if asset_id == MOTOR_ASSET:
                payload = build_motor_payload(now_ms, mode)
                topic = f"phm/telemetry/{MOTOR_ASSET}/{MOTOR_CHANNEL}"
                metrics = payload["metrics"]
            else:
                payload = build_valve_payload(now_ms, mode, state["cycle"])
                state["cycle"] += 1
                topic = f"phm/telemetry/{VALVE_ASSET}/{VALVE_CHANNEL}"
                metrics = payload["metrics"]
            views[asset_id] = {
                "asset_id": asset_id,
                "channel_id": MOTOR_CHANNEL if asset_id == MOTOR_ASSET else VALVE_CHANNEL,
                "modality_type": payload["modality_type"],
                "mode": mode,
                "timestamp": now_ms,
                "metrics": metrics,
                "mqtt_published": publish_multimodal_payload(topic, payload),
            }

        breaker = MULTIMODAL_DEVICES[BREAKER_ASSET]
        if breaker["trigger_now"] or now_seconds - breaker["last_event"] >= 10.0:
            breaker["trigger_now"] = False
            breaker["last_event"] = now_seconds
            telemetry, snapshot = build_breaker_sample(now_ms, breaker["mode"], breaker["cycle"])
            breaker["cycle"] += 1
            telemetry_ok = publish_multimodal_payload(
                f"phm/telemetry/{BREAKER_ASSET}/{BREAKER_CHANNEL}", telemetry)
            snapshot_ok = publish_multimodal_payload(
                f"phm/snapshot/{BREAKER_ASSET}/{BREAKER_CHANNEL}", snapshot)
            views[BREAKER_ASSET] = {
                "asset_id": BREAKER_ASSET,
                "channel_id": BREAKER_CHANNEL,
                "modality_type": telemetry["modality_type"],
                "mode": breaker["mode"],
                "timestamp": now_ms,
                "metrics": telemetry["metrics"],
                "mqtt_published": telemetry_ok and snapshot_ok,
            }
        else:
            current = latest_view_data.get("multimodal_devices", {}).get(BREAKER_ASSET, {})
            views[BREAKER_ASSET] = {**current, "mode": breaker["mode"], "mqtt_published": bool(current.get("mqtt_published"))}
        latest_view_data["multimodal_devices"] = views

def on_message(client, userdata, message):
    topic = message.topic or ""
    if topic.startswith(f"devices/{CONFIG['asset_id']}/") and topic.endswith("/cmd/capture_waveform"):
        parts = topic.split("/")
        if len(parts) >= 4:
            device_state["capture_requested"].add(parts[2])

if CONFIG["enable_mqtt"]:
    try:
        mqtt_client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, "pdm_sim_temp_node")
        mqtt_client.connect(CONFIG["mqtt_broker"], CONFIG["mqtt_port"], 60)
        mqtt_client.on_message = on_message
        mqtt_client.subscribe(f"devices/{CONFIG['asset_id']}/+/cmd/capture_waveform", qos=1)
        mqtt_client.loop_start()
        print(f"[*] MQTT 已连接至 {CONFIG['mqtt_broker']}:{CONFIG['mqtt_port']}（已订阅专家召唤指令）")
    except Exception as e:
        print(f"[!] MQTT 跳过连接 ({e})，使用本地控制台。")
        mqtt_client = None

# ======================= 3. 物理信号合成 (含热力学计算) =======================
def get_target_temperature(mode, rpm):
    """根据工况机理计算目标热平衡稳态温度"""
    t_env = CONFIG["ambient_temp"]
    if mode == "STOPPED" or rpm < 50:
        return t_env
    elif mode == "DETACHED":
        return t_env  # 探头掉落后感知的是空气温度
    elif mode == "HEALTHY":
        return 48.0 + (rpm - 1500.0) * 0.01  # 转速微升温
    elif mode == "UNBALANCE":
        return 56.5   # 离心偏心力轻微生热
    elif mode == "MISALIGNMENT":
        return 68.0   # 联轴器别劲弯曲发热
    elif mode == "LOOSENESS":
        return 54.0   # 摩擦生热有限
    elif mode == "BEARING_FAULT":
        return 88.5   # 轴承干磨剥落，剧烈温升
    elif mode == "GEARBOX_FAULT":
        return 72.0   # 齿面磨损冲击发热
    elif mode == "CAVITATION":
        return 62.0   # 水力气蚀紊流温升
    elif mode == "OIL_WHIRL":
        return 65.0   # 油膜剪切生热
    return 50.0

def generate_vibration_signal(rpm, mode, fs=4096, n=4096, has_spike=False):
    t = np.linspace(0, (n - 1) / fs, n)
    f1 = max(0.1, rpm / 60.0)

    if mode == "STOPPED" or rpm < 50.0:
        sig = np.random.normal(0, 0.008, n)
    elif mode == "STOPPING":
        res_factor = 3.5 if (700.0 <= rpm <= 950.0) else 1.0
        sig = 0.40 * res_factor * np.sin(2 * np.pi * f1 * t) + np.random.normal(0, 0.03, n)
    elif mode == "HEALTHY":
        sig = 0.45 * np.sin(2 * np.pi * f1 * t) + 0.12 * np.sin(2 * np.pi * 2 * f1 * t) + np.random.normal(0, 0.08, n)
    elif mode == "UNBALANCE":
        sig = 2.65 * np.sin(2 * np.pi * f1 * t) + 0.20 * np.sin(2 * np.pi * 2 * f1 * t) + np.random.normal(0, 0.10, n)
    elif mode == "MISALIGNMENT":
        sig = 1.10 * np.sin(2 * np.pi * f1 * t) + 2.30 * np.sin(2 * np.pi * 2 * f1 * t) + 0.85 * np.sin(2 * np.pi * 3 * f1 * t) + np.random.normal(0, 0.12, n)
    elif mode == "LOOSENESS":
        sig = 0.65 * np.sin(2 * np.pi * 0.5 * f1 * t) + 1.40 * np.sin(2 * np.pi * f1 * t) + 1.10 * np.sin(2 * np.pi * 2 * f1 * t) + 0.85 * np.sin(2 * np.pi * 3 * f1 * t) + np.random.normal(0, 0.15, n)
        sig = np.clip(sig, -1.8, 1.8)
    elif mode == "BEARING_FAULT":
        bpfo = f1 * BPFO_FACTOR  # 与平台轴承字典库 SKF 6208 特征频率一致
        period = int(fs / max(1.0, bpfo))
        shock = np.zeros(n)
        for i in range(0, n, max(1, period)):
            decay_len = min(64, n - i)
            # 真实采样时钟：以 j/fs 计相位与衰减，避免 linspace(0,0.02) 引起的载波混叠
            t_decay = np.arange(decay_len) / fs
            shock[i:i+decay_len] += 3.8 * np.exp(-140 * t_decay) * np.sin(2 * np.pi * 1250 * t_decay)
        sig = 0.50 * np.sin(2 * np.pi * f1 * t) + shock + np.random.normal(0, 0.12, n)
    elif mode == "GEARBOX_FAULT":
        z = 18
        gmf = z * f1
        sig = 0.60 * np.sin(2 * np.pi * f1 * t) + (1.0 + 0.65 * np.cos(2 * np.pi * f1 * t)) * 1.85 * np.sin(2 * np.pi * gmf * t) + np.random.normal(0, 0.15, n)
    elif mode == "CAVITATION":
        sig = 0.55 * np.sin(2 * np.pi * f1 * t) + 1.65 * np.sin(2 * np.pi * 6 * f1 * t) + np.random.normal(0, 0.85, n)
    elif mode == "OIL_WHIRL":
        sig = 2.35 * np.sin(2 * np.pi * 0.44 * f1 * t) + 0.55 * np.sin(2 * np.pi * f1 * t) + np.random.normal(0, 0.08, n)
    elif mode == "DETACHED":
        sig = np.random.normal(0, 0.015, n)
    else:
        sig = np.random.normal(0, 0.05, n)

    if has_spike:
        sp_idx = random.randint(int(n * 0.3), int(n * 0.7))
        w = 20
        sig[sp_idx:sp_idx+w] += np.hanning(w * 2)[:w] * random.uniform(7.0, 9.5)

    return t, sig

def compute_features(sig, fs, f1, base_amp, rpm, temp, mode):
    n = len(sig)
    v_rms = float(np.sqrt(np.mean(sig ** 2)))
    a_peak = float(np.max(np.abs(sig)))
    std_val = np.std(sig)
    kurtosis = float(np.mean((sig - np.mean(sig)) ** 4) / (std_val ** 4)) if std_val > 1e-6 else 3.0

    window = np.hanning(n)
    win_corr = np.mean(window)
    fft_vals = np.fft.rfft(sig * window)
    fft_amps = np.abs(fft_vals) / (n / 2) / win_corr
    fft_freqs = np.fft.rfftfreq(n, d=1.0 / fs)

    freq_1x_amp = extract_band_energy_rms(fft_freqs, fft_amps, f1)
    freq_2x_amp = extract_band_energy_rms(fft_freqs, fft_amps, f1 * 2.0)
    freq_3x_amp = extract_band_energy_rms(fft_freqs, fft_amps, f1 * 3.0)
    freq_sub_amp = extract_band_energy_rms(fft_freqs, fft_amps, f1 * 0.44)
    crest_factor = float(a_peak / v_rms) if v_rms > 1e-6 else 0.0

    # Demodulate the 1.0–1.8 kHz resonance band; measure BPFO/BPFI envelope bands
    # with platform bearing library (SKF 6208) consistent factors.
    bpfo_target = f1 * BPFO_FACTOR
    bpfi_target = f1 * BPFI_FACTOR
    env_rms, envelope_bpfo_amp, envelope_bpfi_amp = extract_envelope_features(sig, fs, bpfo_target, bpfi_hz=bpfi_target)
    sei_index = min(1.0, (envelope_bpfo_amp + envelope_bpfi_amp) / env_rms) if env_rms > 1e-6 else 0.0

    # 1. 振动与频率诊断
    diagnostic_text = "状态正常"
    if rpm < 100 or v_rms < 0.03:
        health_index = 100.0
        diagnostic_text = "设备停机备用 (STANDBY)"
    elif v_rms < 0.05 and temp <= (CONFIG["ambient_temp"] + 2.0):
        health_index = 0.0
        diagnostic_text = "探头脱落 (零振动且跌至室温)"
    else:
        k1 = freq_1x_amp / max(0.01, base_amp)
        hi_vib = 100.0 / (1.0 + math.pow(max(0.0, k1 - 1.0) / 1.5, 2.2)) if k1 > 1.15 else 100.0

        if mode == "UNBALANCE":
            diagnostic_text = "转子动不平衡 (1X基频升高)"
        elif mode == "MISALIGNMENT":
            hi_vib = min(hi_vib, 35.0)
            diagnostic_text = "轴系不对中 (2X高过1X，伴随温升)"
        elif mode == "LOOSENESS":
            hi_vib = min(hi_vib, 40.0)
            diagnostic_text = "机械结构松动 (多倍频谐波密集)"
        elif mode == "BEARING_FAULT" or kurtosis > 5.0:
            hi_vib = min(hi_vib, max(15.0, 100.0 - (kurtosis - 3.0) * 14.0))
            diagnostic_text = "轴承严重剥落干磨 (高频冲击+剧烈温升)"
        elif mode == "GEARBOX_FAULT":
            hi_vib = min(hi_vib, 30.0)
            diagnostic_text = "齿轮啮合缺陷 (GMF 450Hz与边频带)"
        elif mode == "CAVITATION":
            hi_vib = min(hi_vib, 45.0)
            diagnostic_text = "水力气蚀喘振 (高能宽频流体噪声)"
        elif mode == "OIL_WHIRL":
            hi_vib = min(hi_vib, 25.0)
            diagnostic_text = "油膜涡动 (0.44X次同步剧烈自激)"

        # 2. 温度独立评价模型 (结合工业经验: 70℃关注, 80℃预警, 90℃危险)
        hi_temp = 100.0
        if temp > 90.0:
            hi_temp = 10.0
        elif temp > 80.0:
            hi_temp = 40.0 - ((temp - 80.0) / 10.0) * 25.0
        elif temp > 65.0:
            hi_temp = 75.0 - ((temp - 65.0) / 15.0) * 35.0

        # 3. 振动绝对烈度兜底
        hi_abs = 100.0
        if v_rms > 4.5:
            hi_abs = max(10.0, 30.0 - (v_rms - 4.5) * 10.0)

        # 融合取木桶短板
        health_index = max(0.0, min(100.0, min([hi_vib, hi_temp, hi_abs])))

    # 温度状态评级
    temp_status = "正常"
    if temp >= 85.0: temp_status = "高温危险"
    elif temp >= 70.0: temp_status = "温升预警"
    elif temp <= (CONFIG["ambient_temp"] + 1.0) and rpm >= 500: temp_status = "脱落低温"

    return {
        "v_rms": round(v_rms, 3),
        "a_peak": round(a_peak, 2),
        "kurtosis": round(kurtosis, 2),
        "temperature": round(temp, 1),
        "temp_status": temp_status,
        "freq_1x_amp": round(freq_1x_amp, 3),
        "freq_2x_amp": round(freq_2x_amp, 3),
        "freq_3x_amp": round(freq_3x_amp, 3),
        "envelope_rms": round(env_rms, 3),
        "envelope_bpfo_amp": round(envelope_bpfo_amp, 3),
        "envelope_bpfi_amp": round(envelope_bpfi_amp, 3),
        "freq_sub_amp": round(freq_sub_amp, 3),
        "crest_factor": round(crest_factor, 2),
        "sei_index": round(sei_index, 3),
        "health_index": round(health_index, 1),
        "status": diagnostic_text,
        "base_amp": round(base_amp, 3),
        "multiplier": round(freq_1x_amp / max(0.01, base_amp), 2),
    }, fft_freqs, fft_amps

# ======================= 3.5 协议 2.0.0 报文构建（文档 §2.1/§2.2） =======================
def is_steady_state(mode, rpm, target_rpm):
    return abs(rpm - target_rpm) <= 5.0 and mode in ("HEALTHY", "UNBALANCE", "MISALIGNMENT",
                                                    "LOOSENESS", "BEARING_FAULT", "GEARBOX_FAULT",
                                                    "CAVITATION", "OIL_WHIRL")


def load_current_for_mode(mode, base):
    factor = {
        "STOPPED": 0.0, "STOPPING": 0.6, "DETACHED": 0.0, "HEALTHY": 1.0,
        "UNBALANCE": 1.12, "MISALIGNMENT": 1.18, "LOOSENESS": 1.05,
        "BEARING_FAULT": 1.25, "GEARBOX_FAULT": 1.2, "CAVITATION": 1.15, "OIL_WHIRL": 1.1,
    }.get(mode, 1.0)
    return round(base * factor, 1)


def build_protocol_payload(timestamp, asset_id, point_code, metrics, rpm, target_rpm, mode, ambient_temp):
    """协议 2.0.0 慢遥测报文（devices/{asset_id}/{point_id}/telemetry，文档 §2.1）。"""
    temp_bearing = metrics.get("temperature", ambient_temp)
    return {
        "protocol_version": "2.0.0",
        "timestamp": timestamp,
        "asset_id": asset_id,
        "point_id": point_code,
        "data_quality": metrics.get("data_quality", 0),
        "operating_condition": {
            "rpm": round(rpm, 1),
            "load_current": load_current_for_mode(mode, CONFIG["load_current"]),
            "is_steady": is_steady_state(mode, rpm, target_rpm),
        },
        "metrics": {
            "temp_bearing": temp_bearing,
            "temp_ambient": round(ambient_temp + random.uniform(-0.3, 0.3), 1),
            "v_rms": metrics.get("v_rms", 0.0),
            "a_peak": metrics.get("a_peak", 0.0),
            "a_kurtosis": metrics.get("kurtosis", 3.0),
            "crest_factor": metrics.get("crest_factor", 0.0),
            "feat_1x": metrics.get("freq_1x_amp", 0.0),
            "feat_2x": metrics.get("freq_2x_amp", 0.0),
            "feat_3x": metrics.get("freq_3x_amp", 0.0),
            "feat_sub": metrics.get("freq_sub_amp", 0.0),
            "env_rms": metrics.get("envelope_rms", 0.0),
            "env_bpfo": metrics.get("envelope_bpfo_amp", 0.0),
            "env_bpfi": metrics.get("envelope_bpfi_amp", 0.0),
            "sei_index": metrics.get("sei_index", 0.0),
        },
    }


def build_legacy_payload(timestamp, point_code, metrics, rpm):
    """旧 v1 契约载荷（pdm/v1/FACT_01/PUMP_P102A/{point}/telemetry），保持平台向后兼容。"""
    return {
        "timestamp": timestamp,
        "factory_id": "FACT_01",
        "device_code": "PUMP_P102A",
        "point_code": point_code,
        "data_quality": metrics.get("data_quality", 0),
        "telemetry": {
            "rpm": round(rpm, 1),
            "temperature": metrics.get("temperature", 24.0),
            "temp_status": metrics.get("temp_status", "正常"),
            "v_rms": metrics.get("v_rms", 0.0),
            "a_peak": metrics.get("a_peak", 0.0),
            "a_kurtosis": metrics.get("kurtosis", 3.0),
            "freq_1x_amp": metrics.get("freq_1x_amp", 0.0),
            "freq_2x_amp": metrics.get("freq_2x_amp", 0.0),
            "envelope_rms": metrics.get("envelope_rms", 0.0),
            "envelope_bpfo_amp": metrics.get("envelope_bpfo_amp", 0.0),
            "health_index": metrics.get("health_index", 100.0),
        },
    }


def publish_vibration_telemetry(client, timestamp, point_code, point_metrics, rpm, target_rpm, mode, ambient_temp,
                                protocol_mode=None):
    """按协议模式发布单条权威慢遥测：默认 2.0.0（含 BPFI/温升/阶次等全量特征），避免 v1+2.0 同时间戳互锁。"""
    if not client:
        return None
    effective_mode = protocol_mode if protocol_mode is not None else CONFIG.get("protocol_mode", "2.0")
    if effective_mode == "v1":
        legacy = build_legacy_payload(timestamp, point_code, point_metrics, rpm)
        topic = f"{CONFIG['legacy_topic_prefix']}/{point_code}/telemetry"
        client.publish(topic, json.dumps(legacy), qos=1)
        return topic, legacy
    protocol = build_protocol_payload(timestamp, CONFIG["asset_id"], point_code, point_metrics,
                                      rpm, target_rpm, mode, ambient_temp)
    topic = f"devices/{CONFIG['asset_id']}/{point_code}/telemetry"
    client.publish(topic, json.dumps(protocol), qos=1)
    return topic, protocol


def publish_slow_telemetry(client, point_results, timestamp, mode, has_spike, rpm, target_rpm, ambient_temp):
    """逐测点发布慢遥测（simulation_worker 的发布循环）。

    瞬态飞刺（has_spike）保持 data_quality=0：平台对质量非 0 的帧走 SENSOR_CHECK 清窗，
    飞刺必须以传感器质量正常进入 SpikeFilter 才会产出 SPIKE_SUPPRESSED；仅 DETACHED 脱落发 3。
    发布测点按 CONFIG["mqtt_point_codes"] 白名单过滤（空/None=全部），默认仅平台已种子的 DE_RADIAL，
    消除未注册测点每秒 404 拒收噪音；本地仪表盘 latest_view_data["sensor_points"] 仍展示全部测点。
    """
    for result in point_results:
        point_metrics = dict(result["metrics"])
        point_metrics["data_quality"] = 3 if mode == "DETACHED" else 0
        point_code = result["sensor"]["point_code"]
        if not CONFIG.get("mqtt_point_codes") or point_code in CONFIG["mqtt_point_codes"]:
            publish_vibration_telemetry(client, timestamp, point_code, point_metrics,
                                        rpm, target_rpm, mode, ambient_temp)


def build_waveform_payload(timestamp, asset_id, point_code, trigger_type, signal, fs, health_index):
    """原始波形快照报文（devices/{asset_id}/{point_id}/raw_waveform，文档 §2.2）。

    preview_waveform 全量回传采样点阵（JSON 数组），storage_type 记录边缘本地缓存。
    """
    preview = [round(float(value), 3) for value in signal]
    return {
        "protocol_version": "2.0.0",
        "timestamp": timestamp,
        "asset_id": asset_id,
        "point_id": point_code,
        "trigger_type": trigger_type,
        "sampling_rate": fs,
        "points_count": len(preview),
        "unit": "m/s^2",
        "storage_type": "LOCAL_EDGE",
        "raw_file_url": None,
        "preview_waveform": preview,
        "health_index": round(health_index, 1),
    }


def waveform_threshold_hit(metrics):
    """越限触发判据：烈度/峭度突破或健康指数崩落。"""
    return (metrics.get("v_rms", 0.0) > 2.8
            or metrics.get("kurtosis", 3.0) > 5.0
            or metrics.get("health_index", 100.0) < 50.0)


# ======================= 4. 模拟循环与热动力学后台线程 =======================
def simulation_worker():
    global latest_view_data
    while True:
        # 1. 转速平滑过渡
        if device_state["current_rpm"] < device_state["target_rpm"]:
            device_state["current_rpm"] = min(device_state["target_rpm"], device_state["current_rpm"] + 150.0)
        elif device_state["current_rpm"] > device_state["target_rpm"]:
            device_state["current_rpm"] = max(device_state["target_rpm"], device_state["current_rpm"] - 120.0)

        # 2. 温度一阶热惯性演化: T(k+1) = T(k) + alpha * (T_target - T(k))
        target_t = get_target_temperature(device_state["mode"], device_state["current_rpm"])
        alpha = 0.12  # 热响应时间常数系数 (每秒追随温差的 12%)
        noise = random.uniform(-0.15, 0.15)
        device_state["current_temp"] += alpha * (target_t - device_state["current_temp"]) + noise

        if device_state["mode"] == "STOPPING" and device_state["current_rpm"] <= 0.0:
            device_state["mode"] = "STOPPED"
        elif device_state["mode"] == "STOPPED" and device_state["current_rpm"] > 0.0:
            device_state["mode"] = "HEALTHY"

        rpm = device_state["current_rpm"]
        mode = device_state["mode"]
        temp = device_state["current_temp"]
        fs = CONFIG["sample_rate"]
        n = CONFIG["sample_points"]

        has_spike = device_state["spike_injected"]
        device_state["spike_injected"] = False

        f1 = max(0.1, rpm / 60.0)
        point_results = []
        for sensor in SENSOR_POINTS:
            t, sig = generate_vibration_signal(rpm, mode, fs, n, has_spike)
            sig = sig * sensor["amplitude_scale"]
            base_amp = (
                device_state["base_1x_amp"]
                if sensor["point_code"] == "DE_RADIAL"
                else sensor["base_1x_amp"]
            )
            metrics, freqs, amps = compute_features(sig, fs, f1, base_amp, rpm, temp, mode)
            point_results.append({
                "sensor": sensor,
                "time": t,
                "signal": sig,
                "metrics": metrics,
                "freqs": freqs,
                "amps": amps,
            })

        primary = next(result for result in point_results if result["sensor"]["point_code"] == "DE_RADIAL")
        t, sig = primary["time"], primary["signal"]
        metrics, freqs, amps = primary["metrics"], primary["freqs"], primary["amps"]

        step = 16
        cutoff_idx = int(550 / (fs / n))

        latest_view_data = {
            "metrics": metrics,
            "mode": mode,
            "current_rpm": round(rpm, 1),
            "current_temp": round(temp, 1),
            "had_spike": has_spike,
            "multimodal_devices": latest_view_data.get("multimodal_devices", {}),
            "sensor_points": [
                {
                    "point_code": result["sensor"]["point_code"],
                    "point_name": result["sensor"]["point_name"],
                    "v_rms": result["metrics"]["v_rms"],
                    "freq_1x_amp": result["metrics"]["freq_1x_amp"],
                    "health_index": result["metrics"]["health_index"],
                    "temperature": result["metrics"]["temperature"],
                    "status": result["metrics"]["status"],
                }
                for result in point_results
            ],
            "time_waveform": [{"t": round(float(t[i]), 3), "y": round(float(sig[i]), 3)} for i in range(0, n, step)],
            "fft_spectrum": {
                "freqs": [round(float(f), 1) for f in freqs[:cutoff_idx]],
                "amps": [round(float(a), 3) for a in amps[:cutoff_idx]]
            }
        }

        # 3. 慢遥测发布：默认单发 PHM 2.0.0 全量特征报文（兼容切回 v1，禁止同时间戳双通道互锁）
        timestamp = int(time.time() * 1000)
        if mqtt_client:
            publish_slow_telemetry(mqtt_client, point_results, timestamp, mode, has_spike,
                                   rpm, device_state["target_rpm"], CONFIG["ambient_temp"])

        # 4. 原始波形快照三类触发：越限（限流）/ 定时巡检 / 专家召唤
        primary_metrics = primary["metrics"]
        now_seconds = time.time()
        if mqtt_client:
            for result in point_results:
                point_code = result["sensor"]["point_code"]
                trigger = None
                if point_code == "DE_RADIAL" and waveform_threshold_hit(primary_metrics) \
                        and now_seconds - device_state["last_threshold_waveform"] >= CONFIG["threshold_waveform_cooldown"]:
                    trigger = "THRESHOLD_EXCEEDED"
                    device_state["last_threshold_waveform"] = now_seconds
                elif point_code == "DE_RADIAL" \
                        and now_seconds - device_state["last_scheduled_waveform"] >= CONFIG["scheduled_waveform_interval"]:
                    trigger = "SCHEDULED"
                    device_state["last_scheduled_waveform"] = now_seconds
                elif point_code in device_state["capture_requested"]:
                    trigger = "ON_DEMAND"
                    device_state["capture_requested"].discard(point_code)
                if trigger:
                    waveform = build_waveform_payload(timestamp, CONFIG["asset_id"], point_code, trigger,
                                                      result["signal"], fs, primary_metrics.get("health_index", 100.0))
                    mqtt_client.publish(f"devices/{CONFIG['asset_id']}/{point_code}/raw_waveform",
                                        json.dumps(waveform), qos=1)
                    print(f"[*] 波形快照已上报 {point_code} trigger={trigger}")

        update_multimodal_simulation()

        time.sleep(1.0)

# ======================= 5. Web 控制与 API =======================
@app.route("/")
def index():
    return render_template_string(HTML_TEMPLATE)

@app.route("/api/data")
def get_data():
    return jsonify(latest_view_data)

@app.route("/api/control", methods=["POST"])
def control():
    req = request.json or {}
    act = req.get("action")

    if act == "toggle_power":
        if device_state["target_rpm"] > 0:
            device_state["target_rpm"] = 0.0
            device_state["mode"] = "STOPPING"
            device_state["is_running"] = False
        else:
            device_state["target_rpm"] = 1500.0
            device_state["is_running"] = True
            device_state["mode"] = "HEALTHY"

    elif act == "inject_spike":
        device_state["spike_injected"] = True

    elif act == "set_mode":
        m = req.get("mode")
        device_state["mode"] = m
        if m not in ["STOPPING", "STOPPED"] and device_state["target_rpm"] == 0:
            device_state["target_rpm"] = 1500.0
            device_state["is_running"] = True

    elif act == "set_rpm":
        r = float(req.get("rpm", 1500.0))
        device_state["target_rpm"] = r
        device_state["is_running"] = (r > 0)

    elif act == "reset_baseline":
        device_state["base_1x_amp"] = latest_view_data["metrics"].get("freq_1x_amp", 0.45)
        device_state["base_temp"] = latest_view_data["metrics"].get("temperature", 48.0)
        latest_point_baselines = {
            point["point_code"]: point["freq_1x_amp"]
            for point in latest_view_data["sensor_points"]
        }
        for sensor in SENSOR_POINTS:
            if sensor["point_code"] != "DE_RADIAL" and sensor["point_code"] in latest_point_baselines:
                sensor["base_1x_amp"] = latest_point_baselines[sensor["point_code"]]

    elif act == "set_device_mode":
        asset_id = req.get("asset_id")
        mode = req.get("mode")
        if not isinstance(asset_id, str) or not isinstance(mode, str) \
                or asset_id not in MULTIMODAL_MODES or mode not in MULTIMODAL_MODES[asset_id]:
            return jsonify({"success": False, "error": "未知的模拟设备或设备模式"}), 400
        with multimodal_lock:
            MULTIMODAL_DEVICES[asset_id]["mode"] = mode

    elif act == "trigger_breaker":
        with multimodal_lock:
            MULTIMODAL_DEVICES[BREAKER_ASSET]["trigger_now"] = True

    return jsonify({"success": True, "state": device_state})

# ======================= 6. Web 看板 UI =======================
HTML_TEMPLATE = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <title>工业设备多模态预测维护仿真台</title>
  <script src="https://cdn.jsdelivr.net/npm/echarts@5.4.3/dist/echarts.min.js"></script>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { background: #0b1120; color: #f8fafc; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; padding: 20px; }
    .header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #1e293b; padding-bottom: 14px; margin-bottom: 20px; }
    .title { font-size: 20px; font-weight: 600; display: flex; align-items: center; gap: 10px; }
    .badge { padding: 4px 10px; font-size: 12px; border-radius: 999px; background: #0284c7; }
    .grid { display: grid; grid-template-columns: 310px 1fr 340px; gap: 20px; }
    .card { background: #1e293b; border: 1px solid #334155; border-radius: 8px; padding: 16px; margin-bottom: 20px; }
    .card-title { font-size: 13px; color: #94a3b8; font-weight: 600; margin-bottom: 12px; text-transform: uppercase; letter-spacing: 0.5px; }
    .btn-group { display: flex; flex-direction: column; gap: 6px; }
    button { background: #334155; border: 1px solid #475569; color: #f8fafc; padding: 8px 12px; border-radius: 6px; cursor: pointer; font-size: 12.5px; text-align: left; transition: all 0.2s; font-weight: 500; }
    button:hover { background: #475569; border-color: #64748b; }
    button.active { background: #0284c7; border-color: #38bdf8; font-weight: 600; }
    button.danger.active { background: #dc2626; border-color: #f87171; }
    button.warning.active { background: #d97706; border-color: #fbbf24; }
    .power-btn { background: #059669; border-color: #10b981; text-align: center; font-size: 13px; font-weight: 700; }
    .power-btn.off { background: #64748b; border-color: #94a3b8; }
    .spike-btn { background: #7c3aed; border-color: #a78bfa; text-align: center; font-weight: 700; margin-top: 6px; }
    .slider-box { margin-top: 14px; font-size: 13px; color: #cbd5e1; }
    .slider-box input { width: 100%; margin-top: 6px; }
    .chart-container { height: 260px; width: 100%; }
    .metric-row { display: flex; justify-content: space-between; padding: 7px 0; border-bottom: 1px solid #334155; font-size: 13px; }
    .metric-val { font-weight: 600; color: #38bdf8; }
    .score-box { text-align: center; padding: 12px 0; }
    .score-num { font-size: 50px; font-weight: 800; color: #22c55e; }
    .score-status { font-size: 14px; font-weight: 600; margin-top: 4px; }
    .temp-card { background: #0f172a; border: 1px solid #334155; border-radius: 6px; padding: 12px; margin-top: 10px; display: flex; justify-content: space-between; align-items: center; }
    .temp-val { font-size: 28px; font-weight: 800; color: #f59e0b; }
    .sensor-strip { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 20px; }
    .sensor-card { background: #1e293b; border: 1px solid #334155; border-radius: 8px; padding: 12px 16px; }
    .sensor-card strong { display: block; color: #38bdf8; font-size: 13px; margin-bottom: 5px; }
    .sensor-card span { color: #cbd5e1; font-size: 12px; }
    .multimodal-strip { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 20px; }
    .device-card { background: #111c30; border: 1px solid #334155; border-radius: 8px; padding: 14px 16px; min-width: 0; }
    .device-card h3 { font-size: 14px; margin-bottom: 4px; }
    .device-meta { color: #94a3b8; font-size: 11px; margin-bottom: 10px; }
    .device-card select { width: 100%; background: #1e293b; color: #f8fafc; border: 1px solid #475569; border-radius: 6px; padding: 7px; margin-bottom: 8px; }
    .device-card button { width: 100%; text-align: center; margin-bottom: 8px; }
    .device-status { font-size: 12px; color: #22c55e; margin-bottom: 8px; }
    .device-metrics { color: #cbd5e1; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
    .device-note { color: #fbbf24; font-size: 11px; margin-top: 8px; }
    @media (max-width: 900px) { .multimodal-strip { grid-template-columns: 1fr; } }
    @media (max-width: 800px) { .sensor-strip { grid-template-columns: 1fr; } }
  </style>
</head>
<body>
  <div class="header">
    <div class="title">
      <span>工业设备多模态预测维护仿真台</span>
      <span class="badge" id="tag-rpm">1500 RPM</span>
      <span class="badge" style="background:#ea580c;" id="tag-temp">48.0 ℃</span>
      <span class="badge" style="background:#475569;">PUMP_P102A</span>
    </div>
    <div style="font-size: 13px; color: #94a3b8;">
      旋转 2.0.0 双通道 · 多模态 2.1.0 | MQTT 实时接入
    </div>
  </div>

  <div class="sensor-strip">
    <div class="sensor-card"><strong>DE_RADIAL · 驱动端径向</strong><span id="sensor-de-radial">等待数据…</span></div>
    <div class="sensor-card"><strong>NDE_RADIAL · 非驱动端径向</strong><span id="sensor-nde-radial">等待数据…</span></div>
    <div class="sensor-card"><strong>DE_AXIAL · 驱动端轴向</strong><span id="sensor-de-axial">等待数据…</span></div>
  </div>

  <div class="multimodal-strip">
    <section class="device-card">
      <h3>异步电机 M01 · MCSA</h3>
      <div class="device-meta">MOTOR_M01 / CH_STATOR_CURRENT</div>
      <select id="mode-MOTOR_M01" aria-label="异步电机 M01 模拟状态" onchange="setDeviceMode('MOTOR_M01', this.value)">
        <option value="HEALTHY">健康稳态</option>
        <option value="ROTOR_BAR_FAULT">转子导条故障</option>
      </select>
      <div id="status-MOTOR_M01" class="device-status">等待数据…</div>
      <div id="metrics-MOTOR_M01" class="device-metrics">等待电流特征…</div>
      <div class="device-note">MCSA 相对评分可用；确定性告警默认关闭。</div>
    </section>
    <section class="device-card">
      <h3>高压断路器 10kV</h3>
      <div class="device-meta">BREAKER_10KV_02 / CH_TRIP_UNIT</div>
      <select id="mode-BREAKER_10KV_02" aria-label="10kV 断路器模拟状态" onchange="setDeviceMode('BREAKER_10KV_02', this.value)">
        <option value="HEALTHY">健康动作</option>
        <option value="SLOW_TRIP">分闸变慢</option>
        <option value="WEAK_COIL">线圈电流偏弱</option>
      </select>
      <button onclick="triggerBreaker()">⚡ 立即模拟一次分闸</button>
      <div id="status-BREAKER_10KV_02" class="device-status">等待动作…</div>
      <div id="metrics-BREAKER_10KV_02" class="device-metrics">等待动作特征…</div>
    </section>
    <section class="device-card">
      <h3>Feed 调节阀 FCV101</h3>
      <div class="device-meta">FEED_VALVE_FCV101 / CH_VALVE_LOOP</div>
      <select id="mode-FEED_VALVE_FCV101" aria-label="FCV101 调节阀模拟状态" onchange="setDeviceMode('FEED_VALVE_FCV101', this.value)">
        <option value="HEALTHY">正常回环</option>
        <option value="STICK_SLIP">卡涩 / 死区增大</option>
        <option value="LEAK">阀内泄漏</option>
      </select>
      <div id="status-FEED_VALVE_FCV101" class="device-status">等待数据…</div>
      <div id="metrics-FEED_VALVE_FCV101" class="device-metrics">等待过程特征…</div>
    </section>
  </div>

  <div class="grid">
    <!-- 1. 左侧：工况控制器 -->
    <div>
      <div class="card" style="border-color:#38bdf844;">
        <div class="card-title">现场干扰与工况控制</div>
        <button id="btn-power" class="power-btn" style="width:100%; margin-bottom:8px;" onclick="togglePower()">
          ⚡ 运行中 (点击滑行停机)
        </button>
        <button class="spike-btn" style="width:100%;" onclick="injectSpike()">
          💥 注入偶发碰撞飞刺 (防抖测试)
        </button>
      </div>

      <div class="card">
        <div class="card-title">7 大经典故障与温度演化</div>
        <div class="btn-group">
          <button id="btn-HEALTHY" class="active" onclick="setMode('HEALTHY')">🟢 正常健康稳态 (~48℃)</button>
          <button id="btn-UNBALANCE" class="danger" onclick="setMode('UNBALANCE')">🔴 1. 转子动不平衡 (~56℃)</button>
          <button id="btn-MISALIGNMENT" class="danger" onclick="setMode('MISALIGNMENT')">🔴 2. 轴系不对中 (~68℃发热)</button>
          <button id="btn-LOOSENESS" class="warning" onclick="setMode('LOOSENESS')">🟠 3. 机械结构松动 (~54℃)</button>
          <button id="btn-BEARING_FAULT" class="warning" onclick="setMode('BEARING_FAULT')">🟠 4. 轴承严重剥落 (~88℃高温)</button>
          <button id="btn-GEARBOX_FAULT" class="warning" onclick="setMode('GEARBOX_FAULT')">🟠 5. 齿轮箱缺陷 (~72℃)</button>
          <button id="btn-CAVITATION" class="warning" onclick="setMode('CAVITATION')">🟠 6. 水力气蚀喘振 (~62℃)</button>
          <button id="btn-OIL_WHIRL" class="danger" onclick="setMode('OIL_WHIRL')">🔴 7. 油膜涡动 (~65℃)</button>
          <button id="btn-DETACHED" onclick="setMode('DETACHED')">⚪ 8. 传感器脱落 (跌至室温)</button>
        </div>

        <div class="slider-box">
          <div style="display:flex; justify-content:space-between;">
            <span>主轴转速 (RPM)</span>
            <span id="rpm-slider-val" style="color:#38bdf8; font-weight:600;">1500 RPM</span>
          </div>
          <input type="range" id="rpm-slider" min="600" max="1800" step="50" value="1500" oninput="setRpm(this.value)">
        </div>

        <button style="margin-top:14px; text-align:center; width:100%;" onclick="resetBaseline()">
          🎯 锁定当前幅值与温度为基线
        </button>
      </div>
    </div>

    <!-- 2. 中间：实时波形与频谱图 -->
    <div>
      <div class="card">
        <div class="card-title">实时时域振动波形 (Time Domain) - 速度 (mm/s)</div>
        <div id="chart-time" class="chart-container"></div>
      </div>
      <div class="card" style="margin-bottom:0;">
        <div class="card-title">实时 FFT 幅值频谱 (Frequency Domain) - 阶次与故障峰</div>
        <div id="chart-fft" class="chart-container"></div>
      </div>
    </div>

    <!-- 3. 右侧：指标与振温综合诊断 -->
    <div>
      <div class="card">
        <div class="card-title">设备综合健康指数 (HI)</div>
        <div class="score-box">
          <div class="score-num" id="hi-score">100</div>
          <div class="score-status" id="hi-status">状态正常</div>
        </div>

        <!-- 轴承表面温度卡片 -->
        <div class="temp-card">
          <div>
            <div style="font-size:12px; color:#94a3b8;">轴承座表面温度</div>
            <div id="m-temp-status" style="font-size:13px; font-weight:700; color:#22c55e; margin-top:2px;">正常稳态</div>
          </div>
          <div class="temp-val" id="m-temp">48.0 ℃</div>
        </div>

        <div style="margin-top:14px;">
          <div class="metric-row"><span>专家定性诊断:</span><span class="metric-val" id="m-diag" style="color:#f59e0b;">-</span></div>
          <div class="metric-row"><span>实时转速:</span><span class="metric-val" id="m-rpm">1500 RPM</span></div>
          <div class="metric-row"><span>1X 转频振幅:</span><span class="metric-val" id="m-1x">-</span></div>
          <div class="metric-row"><span>2X 不对中振幅:</span><span class="metric-val" id="m-2x">-</span></div>
          <div class="metric-row"><span>相对恶化倍率 (k):</span><span class="metric-val" id="m-k">-</span></div>
          <div class="metric-row"><span>振动烈度 (RMS):</span><span class="metric-val" id="m-rms">-</span></div>
          <div class="metric-row"><span>波形峭度 (Kurtosis):</span><span class="metric-val" id="m-kurt">-</span></div>
        </div>
      </div>
    </div>
  </div>

  <script>
    const chartTime = echarts.init(document.getElementById('chart-time'));
    const chartFft = echarts.init(document.getElementById('chart-fft'));

    chartTime.setOption({
      grid: { top: 20, right: 20, bottom: 25, left: 45 },
      xAxis: { type: 'category', data: [], axisLabel: { color: '#64748b' } },
      yAxis: { type: 'value', min: -8.0, max: 8.0, axisLabel: { color: '#64748b' }, splitLine: { lineStyle: { color: '#334155' } } },
      series: [{ type: 'line', data: [], smooth: true, showSymbol: false, lineStyle: { color: '#38bdf8', width: 1.5 } }]
    });

    chartFft.setOption({
      grid: { top: 20, right: 20, bottom: 25, left: 45 },
      xAxis: { type: 'category', data: [], axisLabel: { color: '#64748b' } },
      yAxis: { type: 'value', min: 0, max: 3.5, axisLabel: { color: '#64748b' }, splitLine: { lineStyle: { color: '#334155' } } },
      series: [{ type: 'bar', data: [], barWidth: '60%', itemStyle: { color: '#0284c7' } }]
    });

    function togglePower() {
      fetch('/api/control', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: 'toggle_power' }) });
    }
    function injectSpike() {
      fetch('/api/control', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: 'inject_spike' }) });
    }
    function setMode(mode) {
      document.querySelectorAll('.btn-group button').forEach(b => b.classList.remove('active'));
      const target = document.getElementById('btn-' + mode);
      if (target) target.classList.add('active');
      fetch('/api/control', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: 'set_mode', mode: mode }) });
    }
    function setRpm(rpm) {
      document.getElementById('rpm-slider-val').innerText = rpm + ' RPM';
      fetch('/api/control', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: 'set_rpm', rpm: parseFloat(rpm) }) });
    }
    function resetBaseline() {
      fetch('/api/control', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: 'reset_baseline' }) }).then(() => alert('健康基线已锁定！'));
    }
    function setDeviceMode(assetId, mode) {
      fetch('/api/control', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'set_device_mode', asset_id: assetId, mode }) });
    }
    function triggerBreaker() {
      fetch('/api/control', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'trigger_breaker' }) });
    }
    function renderMultimodalDevices(devices) {
      const specs = {
        MOTOR_M01: m => `工频 ${m.mains_freq_hz} Hz · 转差 ${m.slip_pct}% · 边频带 ${m.sideband_amp_db} dB · 载波 ${m.carrier_amp_db} dB`,
        BREAKER_10KV_02: m => `分闸 ${m.trip_duration_ms} ms · 线圈峰值 ${m.peak_coil_current_a} A · 合闸速度 ${m.closing_velocity_mps} m/s · 磨损 ${m.contact_wear_ratio}`,
        FEED_VALVE_FCV101: m => `死区 ${m.hysteresis_deadband_pct}% · 行程滞后 ${m.travel_time_lag_ms} ms · 泄漏 ${m.ultrasonic_leak_rms} RMS · CV/FB ${m.cv_pct}% / ${m.fb_pct}%`
      };
      Object.entries(specs).forEach(([assetId, describe]) => {
        const device = devices && devices[assetId];
        if (!device) return;
        const select = document.getElementById('mode-' + assetId);
        if (select) select.value = device.mode;
        const status = document.getElementById('status-' + assetId);
        const metrics = document.getElementById('metrics-' + assetId);
        if (status) status.innerText = device.mqtt_published ? '● MQTT 已发布' : '● 本地模拟，MQTT 未发布';
        if (metrics) metrics.innerText = describe(device.metrics || {});
      });
    }

    setInterval(() => {
      fetch('/api/data').then(res => res.json()).then(data => {
        if (!data || !data.metrics) return;
        renderMultimodalDevices(data.multimodal_devices);
        const m = data.metrics;

        const pBtn = document.getElementById('btn-power');
        if (data.current_rpm <= 0.0) {
          pBtn.className = 'power-btn off'; pBtn.innerText = '⭕ 已停机 (点击启机启动)';
        } else if (data.mode === 'STOPPING') {
          pBtn.className = 'power-btn off'; pBtn.innerText = `⏳ 停机滑行降温中 (${data.current_rpm} RPM)...`;
        } else {
          pBtn.className = 'power-btn'; pBtn.innerText = '⚡ 运行中 (点击滑行停机)';
        }

        document.getElementById('tag-rpm').innerText = data.current_rpm + ' RPM';
        document.getElementById('tag-temp').innerText = m.temperature + ' ℃';
        document.getElementById('m-rpm').innerText = data.current_rpm + ' RPM';
        document.getElementById('m-temp').innerText = m.temperature + ' ℃';
        document.getElementById('m-temp-status').innerText = m.temp_status;

        // 温度颜色联动
        const tempStatusEl = document.getElementById('m-temp-status');
        const tempValEl = document.getElementById('m-temp');
        if (m.temperature >= 85) {
          tempStatusEl.style.color = '#ef4444'; tempValEl.style.color = '#ef4444';
        } else if (m.temperature >= 70) {
          tempStatusEl.style.color = '#f59e0b'; tempValEl.style.color = '#f59e0b';
        } else {
          tempStatusEl.style.color = '#22c55e'; tempValEl.style.color = '#22c55e';
        }

        document.getElementById('m-diag').innerText = m.status;
        document.getElementById('hi-score').innerText = m.health_index;
        document.getElementById('hi-status').innerText = m.health_index >= 70 ? '优良/健康' : (m.health_index >= 50 ? '关注/预警' : '危险/报警');
        document.getElementById('m-1x').innerText = m.freq_1x_amp + ' mm/s';
        document.getElementById('m-2x').innerText = m.freq_2x_amp + ' mm/s';
        document.getElementById('m-k').innerText = m.multiplier + ' x';
        document.getElementById('m-rms').innerText = m.v_rms + ' mm/s';
        document.getElementById('m-kurt').innerText = m.kurtosis;

        const sensorCards = {
          DE_RADIAL: 'sensor-de-radial',
          NDE_RADIAL: 'sensor-nde-radial',
          DE_AXIAL: 'sensor-de-axial'
        };
        (data.sensor_points || []).forEach(point => {
          const card = document.getElementById(sensorCards[point.point_code]);
          if (card) card.innerText = `${point.v_rms} mm/s RMS · HI ${point.health_index} · ${point.status}`;
        });

        const scoreEl = document.getElementById('hi-score');
        if (m.health_index >= 70) scoreEl.style.color = '#22c55e';
        else if (m.health_index >= 50) scoreEl.style.color = '#eab308';
        else scoreEl.style.color = '#ef4444';

        chartTime.setOption({
          xAxis: { data: data.time_waveform.map(p => p.t) },
          series: [{ data: data.time_waveform.map(p => p.y) }]
        });

        chartFft.setOption({
          xAxis: { data: data.fft_spectrum.freqs.map(f => f + 'Hz') },
          series: [{ data: data.fft_spectrum.amps }]
        });
      });
    }, 1000);

    window.addEventListener('resize', () => {
      chartTime.resize();
      chartFft.resize();
    });
  </script>
</body>
</html>
"""

# ======================= 7. 主入口 =======================
if __name__ == "__main__":
    t = threading.Thread(target=simulation_worker, daemon=True)
    t.start()
    print("[*] 振温双参工业仿真引擎已启动 (热力学模型加载完成)")
    print("[*] Web 控制台已就绪: http://127.0.0.1:5000")
    app.run(host="0.0.0.0", port=5000, debug=False)
