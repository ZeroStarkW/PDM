"""合成平台多模态遥测样本；预测评分仍由 PdM 平台负责。"""

import math
import random


MOTOR_ASSET = "MOTOR_M01"
MOTOR_CHANNEL = "CH_STATOR_CURRENT"
BREAKER_ASSET = "BREAKER_10KV_02"
BREAKER_CHANNEL = "CH_TRIP_UNIT"
VALVE_ASSET = "FEED_VALVE_FCV101"
VALVE_CHANNEL = "CH_VALVE_LOOP"

MOTOR_MODES = {"HEALTHY", "ROTOR_BAR_FAULT"}
BREAKER_MODES = {"HEALTHY", "SLOW_TRIP", "WEAK_COIL"}
VALVE_MODES = {"HEALTHY", "STICK_SLIP", "LEAK"}

VALVE_CYCLE = (
    (0.0, 0.0), (25.0, 23.0), (50.0, 48.0), (75.0, 73.0),
    (75.0, 75.0), (50.0, 50.0), (25.0, 25.0), (0.0, 2.0),
)


def build_motor_payload(timestamp: int, mode: str) -> dict:
    """构建 MCSA 特征遥测；工况桥接负载率以满足插件必需特征。"""
    carrier_db = -33.0 + random.uniform(-0.15, 0.15)
    sideband_db = (-93.0 if mode == "HEALTHY" else -64.0) + random.uniform(-0.3, 0.3)
    slip_pct = (2.5 if mode == "HEALTHY" else 4.0) + random.uniform(-0.05, 0.05)
    return {
        "protocol_version": "2.1.0",
        "timestamp": timestamp,
        "asset_id": MOTOR_ASSET,
        "channel_id": MOTOR_CHANNEL,
        "modality_type": "MCSA",
        "data_quality": 0,
        "operating_condition": {"steady_state": True, "load_pct": 75.0, "rpm": 1462.5},
        "metrics": {
            "mains_freq_hz": 50.0,
            "slip_pct": round(slip_pct, 3),
            "sideband_amp_db": round(sideband_db, 3),
            "carrier_amp_db": round(carrier_db, 3),
        },
    }


def build_breaker_sample(timestamp: int, mode: str, cycle: int = 0) -> tuple[dict, dict]:
    """构建一组断路器动作遥测及与其时间戳匹配的曲线快照。"""
    duration = 41.0 + random.uniform(-0.4, 0.4)
    current = 5.05 + random.uniform(-0.08, 0.08)
    velocity = 2.15 + random.uniform(-0.03, 0.03)
    if mode == "SLOW_TRIP":
        duration = 52.0 + random.uniform(-0.5, 0.5)
        velocity = 1.82 + random.uniform(-0.03, 0.03)
    elif mode == "WEAK_COIL":
        current = 3.7 + random.uniform(-0.08, 0.08)
    wear = min(0.99, 0.10 + cycle * 0.0002)
    metrics = {
        "trip_duration_ms": round(duration, 3),
        "peak_coil_current_a": round(current, 3),
        "closing_velocity_mps": round(velocity, 3),
        "contact_wear_ratio": round(wear, 4),
    }
    telemetry = {
        "protocol_version": "2.1.0",
        "timestamp": timestamp,
        "asset_id": BREAKER_ASSET,
        "channel_id": BREAKER_CHANNEL,
        "modality_type": "SWITCHGEAR_EVENT",
        "data_quality": 0,
        "operating_condition": {"steady_state": True, "bus_voltage_kv": 10.2},
        "metrics": metrics,
    }
    time_axis = [0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0, 40.0, 45.0]
    current_scale = current / 5.05
    coil_current = [round(value * current_scale, 3)
                    for value in (0.0, 0.8, 3.2, 5.42, 4.1, 2.0, 0.5, 0.1, 0.0, 0.0)]
    stroke_scale = velocity / 2.15
    travel = [round(value * stroke_scale, 3)
              for value in (0.0, 0.0, 0.2, 1.5, 4.8, 9.2, 14.5, 18.2, 20.0, 20.0)]
    snapshot = {
        "protocol_version": "2.1.0",
        "timestamp": timestamp,
        "asset_id": BREAKER_ASSET,
        "channel_id": BREAKER_CHANNEL,
        "modality_type": "SWITCHGEAR_EVENT",
        "trigger_type": "TRIP_ACTION_BURST",
        "sampling_rate": 10000,
        "points_count": len(coil_current),
        "preview_curve": coil_current,
        "curves": {
            "time_axis_ms": time_axis,
            "coil_current_a": coil_current,
            "travel_stroke_mm": travel,
        },
        "curve_units": {"time_axis_ms": "ms", "coil_current_a": "A", "travel_stroke_mm": "mm"},
        "metrics": {"trip_duration_ms": metrics["trip_duration_ms"],
                    "peak_coil_current_a": metrics["peak_coil_current_a"]},
        "data_quality": 0,
    }
    return telemetry, snapshot


def build_valve_payload(timestamp: int, mode: str, cycle: int) -> dict:
    """构建调节阀连续过程遥测及回环指令/反馈。"""
    command, feedback = VALVE_CYCLE[cycle % len(VALVE_CYCLE)]
    deadband = 1.0 + random.uniform(-0.06, 0.06)
    travel_lag = 400.0 + random.uniform(-8.0, 8.0)
    leakage = 0.04 + random.uniform(0.0, 0.008)
    friction = 1.2 + random.uniform(-0.04, 0.04)
    if mode == "STICK_SLIP":
        deadband = 4.2 + random.uniform(-0.1, 0.1)
        travel_lag = 720.0 + random.uniform(-12.0, 12.0)
        friction = 3.0 + random.uniform(-0.08, 0.08)
        feedback = max(0.0, min(100.0, command - math.copysign(deadband, command - feedback)))
    elif mode == "LEAK":
        leakage = 0.62 + random.uniform(-0.02, 0.02)
    return {
        "protocol_version": "2.1.0",
        "timestamp": timestamp,
        "asset_id": VALVE_ASSET,
        "channel_id": VALVE_CHANNEL,
        "modality_type": "CONTROL_VALVE_PROCESS",
        "data_quality": 0,
        "operating_condition": {"steady_state": True, "load_pct": 80.0},
        "metrics": {
            "hysteresis_deadband_pct": round(deadband, 3),
            "travel_time_lag_ms": round(travel_lag, 3),
            "ultrasonic_leak_rms": round(leakage, 4),
            "friction_force_kn": round(friction, 3),
            "cv_pct": command,
            "fb_pct": round(feedback, 3),
        },
    }
