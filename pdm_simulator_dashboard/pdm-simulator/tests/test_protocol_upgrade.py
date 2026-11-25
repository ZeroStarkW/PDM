"""PHM 协议 2.0.0 升级验收测试：包络解调特征 + 三类报文结构契约（unittest 风格）。"""

import json
import math
import unittest
from unittest.mock import patch

import numpy as np

import signal_features as sf
from main import (CONFIG, build_legacy_payload, build_protocol_payload, build_waveform_payload,
                  is_steady_state, publish_slow_telemetry, publish_vibration_telemetry,
                  waveform_threshold_hit)


def bearing_fault_signal(f1=25.0, fs=8192.0, n=8192):
    """合成 BEARING_FAULT 信号：低幅 1X + BPFO 频率冲击调制（经典外圈点蚀）。"""
    t = np.arange(n) / fs
    bpfo = f1 * sf.BPFO_FACTOR
    sig = 0.50 * np.sin(2 * np.pi * f1 * t) + np.random.normal(0, 0.05, n)
    period = fs / bpfo
    for k in range(int(n / period)):
        start = int(round(k * period))
        t_decay = np.arange(min(64, n - start)) / fs
        sig[start:start + t_decay.size] += 3.0 * np.exp(-140 * t_decay) * np.sin(2 * np.pi * 1250 * t_decay)
    return sig, fs, f1, bpfo


class EnvelopeDetectionTest(unittest.TestCase):
    def test_envelope_features_detect_library_consistent_bpfo(self):
        """包络解调在与平台轴承库一致的 BPFO=3.5395·f1 频带检出特征能量。"""
        sig, fs, f1, bpfo = bearing_fault_signal()
        env_rms, bpfo_amp, bpfi_amp = sf.extract_envelope_features(
            sig, fs, bpfo, bpfi_hz=f1 * sf.BPFI_FACTOR)
        self.assertGreater(env_rms, 0.0)
        self.assertGreater(bpfo_amp, 0.0, "外圈冲击调制必须在 BPFO 频带检出能量")
        self.assertGreater(bpfo_amp, bpfi_amp, "外圈故障的 BPFO 频带能量应显著高于 BPFI")

    def test_envelope_spectrum_peak_matches_bpfo(self):
        sig, fs, f1, bpfo = bearing_fault_signal()
        freqs, spectrum = sf.extract_envelope_spectrum(sig, fs)
        self.assertGreater(freqs.size, 0)
        peak_hz = float(freqs[int(np.argmax(spectrum))])
        self.assertLessEqual(abs(peak_hz - bpfo), max(2.0, bpfo * 0.03),
                             f"包络谱主峰 {peak_hz}Hz 应贴近 BPFO {bpfo:.1f}Hz")


class PayloadContractTest(unittest.TestCase):
    def test_protocol_payload_structure_matches_document(self):
        metrics = {
            "temperature": 48.2, "temp_status": "正常", "v_rms": 0.485, "a_peak": 1.25,
            "kurtosis": 3.02, "crest_factor": 2.58, "freq_1x_amp": 0.452, "freq_2x_amp": 0.118,
            "freq_3x_amp": 0.045, "freq_sub_amp": 0.012, "envelope_rms": 0.155,
            "envelope_bpfo_amp": 0.015, "envelope_bpfi_amp": 0.012, "sei_index": 0.08,
            "data_quality": 0, "health_index": 100.0,
        }
        payload = build_protocol_payload(1732262400000, "PUMP_P102A", "DE_RADIAL", metrics,
                                         1498.5, 1500.0, "HEALTHY", 24.0)
        self.assertEqual(payload["protocol_version"], "2.0.0")
        self.assertEqual(payload["asset_id"], "PUMP_P102A")
        self.assertEqual(payload["point_id"], "DE_RADIAL")
        self.assertEqual(payload["data_quality"], 0)
        condition = payload["operating_condition"]
        self.assertEqual(condition["rpm"], 1498.5)
        self.assertEqual(condition["load_current"], 45.2)
        self.assertTrue(condition["is_steady"])
        expected_metrics = {
            "temp_bearing": 48.2, "v_rms": 0.485, "a_peak": 1.25, "a_kurtosis": 3.02,
            "crest_factor": 2.58, "feat_1x": 0.452, "feat_2x": 0.118, "feat_3x": 0.045,
            "feat_sub": 0.012, "env_rms": 0.155, "env_bpfo": 0.015, "env_bpfi": 0.012,
            "sei_index": 0.08,
        }
        for key, value in expected_metrics.items():
            self.assertEqual(payload["metrics"][key], value, f"metrics.{key} 必须按文档 §2.1 命名映射")
        self.assertTrue(20.0 <= payload["metrics"]["temp_ambient"] <= 28.0)

    def test_legacy_payload_keeps_backward_compatible_shape(self):
        metrics = {"temperature": 48.0, "temp_status": "正常", "v_rms": 1.2, "a_peak": 8.0,
                   "kurtosis": 3.1, "freq_1x_amp": 0.5, "freq_2x_amp": 0.12, "envelope_rms": 0.1,
                   "envelope_bpfo_amp": 0.02, "health_index": 95.0, "data_quality": 0}
        payload = build_legacy_payload(1732262400000, "DE_RADIAL", metrics, 1500.0)
        self.assertEqual(payload["factory_id"], "FACT_01")
        self.assertEqual(payload["device_code"], "PUMP_P102A")
        self.assertEqual(payload["point_code"], "DE_RADIAL")
        self.assertEqual(payload["telemetry"]["freq_1x_amp"], 0.5)
        self.assertEqual(payload["telemetry"]["health_index"], 95.0)

    def test_waveform_payload_carries_trigger_and_preview(self):
        signal = [0.1 * math.sin(i / 5.0) for i in range(512)]
        payload = build_waveform_payload(1732262400000, "PUMP_P102A", "DE_RADIAL",
                                         "THRESHOLD_EXCEEDED", signal, 8192.0, 42.0)
        self.assertEqual(payload["trigger_type"], "THRESHOLD_EXCEEDED")
        self.assertEqual(payload["sampling_rate"], 8192.0)
        self.assertEqual(payload["points_count"], 512)
        self.assertEqual(len(payload["preview_waveform"]), 512)
        self.assertEqual(payload["unit"], "m/s^2")
        self.assertEqual(payload["storage_type"], "LOCAL_EDGE")
        self.assertEqual(payload["health_index"], 42.0)

    def test_trigger_rules_and_steady_state(self):
        self.assertTrue(waveform_threshold_hit({"v_rms": 3.5, "kurtosis": 3.0, "health_index": 80.0}))
        self.assertTrue(waveform_threshold_hit({"v_rms": 1.0, "kurtosis": 6.0, "health_index": 80.0}))
        self.assertTrue(waveform_threshold_hit({"v_rms": 1.0, "kurtosis": 3.0, "health_index": 40.0}))
        self.assertFalse(waveform_threshold_hit({"v_rms": 1.0, "kurtosis": 3.0, "health_index": 90.0}))
        self.assertTrue(is_steady_state("HEALTHY", 1500.0, 1500.0))
        self.assertFalse(is_steady_state("STOPPED", 0.0, 0.0))
        self.assertFalse(is_steady_state("HEALTHY", 900.0, 1500.0))

    def test_publish_vibration_telemetry_avoids_dual_protocol_same_timestamp_lock(self):
        published = []

        class StubMqttClient:
            def publish(self, topic, payload, qos=0):
                published.append((topic, payload, qos))

        metrics = {
            "temperature": 49.5, "temp_status": "正常", "v_rms": 0.62, "a_peak": 1.4,
            "kurtosis": 3.05, "crest_factor": 2.26, "freq_1x_amp": 0.48, "freq_2x_amp": 0.11,
            "freq_3x_amp": 0.04, "freq_sub_amp": 0.01, "envelope_rms": 0.16,
            "envelope_bpfo_amp": 0.02, "envelope_bpfi_amp": 0.018, "sei_index": 0.09,
            "data_quality": 0, "health_index": 98.0,
        }
        client = StubMqttClient()
        topic, payload = publish_vibration_telemetry(
            client, 1732262400000, "DE_RADIAL", metrics, 1499.0, 1500.0, "HEALTHY", 24.0)
        self.assertEqual(len(published), 1, "默认必须单发 PHM 2.0.0 报文，禁止同时间戳先发 v1 锁死扩展特征")
        self.assertEqual(topic, "devices/PUMP_P102A/DE_RADIAL/telemetry")
        self.assertEqual(payload["metrics"]["env_bpfi"], 0.018)
        self.assertIn("temp_ambient", payload["metrics"])

        # 显式切回 v1 模式时只发旧主题
        published.clear()
        v1_topic, v1_payload = publish_vibration_telemetry(
            client, 1732262401000, "DE_RADIAL", metrics, 1499.0, 1500.0, "HEALTHY", 24.0, protocol_mode="v1")
        self.assertEqual(len(published), 1)
        self.assertEqual(v1_topic, "pdm/v1/FACT_01/PUMP_P102A/DE_RADIAL/telemetry")
        self.assertEqual(v1_payload["telemetry"]["freq_1x_amp"], 0.48)


class SlowTelemetryQualityAndWhitelistTest(unittest.TestCase):
    """慢遥测发布循环：飞刺保持 data_quality=0 交平台 SpikeFilter，测点按 mqtt_point_codes 白名单过滤。"""

    POINT_CODES = ("DE_RADIAL", "NDE_RADIAL", "DE_AXIAL")

    def _point_results(self):
        metrics = {"temperature": 48.5, "v_rms": 0.52, "a_peak": 1.3, "kurtosis": 3.05,
                   "freq_1x_amp": 0.46, "freq_2x_amp": 0.12, "envelope_rms": 0.15,
                   "envelope_bpfo_amp": 0.02, "health_index": 99.0}
        return [{"sensor": {"point_code": code, "point_name": code}, "metrics": metrics}
                for code in self.POINT_CODES]

    def _publish(self, mode, has_spike, point_codes=None, patch_codes=True):
        published = []

        class StubClient:
            def publish(self, topic, payload, qos=0):
                published.append((topic, json.loads(payload)))

        overrides = {"mqtt_point_codes": point_codes} if patch_codes else {}
        with patch.dict(CONFIG, overrides):
            results = self._point_results()
            publish_slow_telemetry(StubClient(), results, 1732262400000, mode, has_spike,
                                   1500.0, 1500.0, 24.0)
            return published, results

    def test_default_config_only_publishes_seeded_de_radial_point(self):
        """默认白名单为平台已种子的 DE_RADIAL，未注册测点不再逐秒 404 拒收。"""
        self.assertEqual(CONFIG["mqtt_point_codes"], ["DE_RADIAL"])
        published, _ = self._publish("HEALTHY", False, patch_codes=False)
        self.assertEqual([topic for topic, _ in published],
                         ["devices/PUMP_P102A/DE_RADIAL/telemetry"])

    def test_transient_spike_keeps_data_quality_zero_for_spike_filter(self):
        """注入飞刺时传感器质量保持 0，使平台 SpikeFilter 产出 SPIKE_SUPPRESSED 而非被 SENSOR_CHECK 清窗。"""
        published, _ = self._publish("HEALTHY", True, point_codes=list(self.POINT_CODES))
        self.assertEqual(len(published), 3, "白名单全量时三个测点都应发布")
        for _, payload in published:
            self.assertEqual(payload["data_quality"], 0, "瞬态飞刺帧 data_quality 必须为 0")

    def test_detached_mode_publishes_data_quality_three(self):
        published, _ = self._publish("DETACHED", False, patch_codes=False)
        self.assertEqual(len(published), 1, "默认白名单下仅发布 DE_RADIAL")
        self.assertEqual(published[0][1]["data_quality"], 3, "仅传感器脱落才发布 data_quality=3")

    def test_empty_or_none_whitelist_publishes_all_sensor_points(self):
        for point_codes in ([], None):
            with self.subTest(mqtt_point_codes=point_codes):
                published, _ = self._publish("HEALTHY", False, point_codes=point_codes)
                self.assertEqual(len(published), len(self.POINT_CODES),
                                 "白名单为空或 None 时应发布全部 SENSOR_POINTS")
                self.assertEqual({payload["point_id"] for _, payload in published},
                                 set(self.POINT_CODES))

    def test_slow_telemetry_publish_does_not_mutate_view_metrics(self):
        """发布循环对指标做副本注入 data_quality，本地仪表盘 sensor_points 视图数据不被污染。"""
        _, results = self._publish("HEALTHY", True, point_codes=list(self.POINT_CODES))
        for result in results:
            self.assertNotIn("data_quality", result["metrics"])


if __name__ == "__main__":
    unittest.main()
