package com.pdm.mvp.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.DeviceRepository;
import com.pdm.mvp.asset.FactoryEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.controlvalve.ControlValvePlugin;
import com.pdm.mvp.controlvalve.ControlValveProperties;
import com.pdm.mvp.modality.BaselineSnapshot;
import com.pdm.mvp.modality.ModalityPlugin;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.switchgear.CircuitBreakerPlugin;
import com.pdm.mvp.switchgear.SwitchgearProperties;
import com.pdm.mvp.telemetry.OperatingCondition;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 基线锁定收敛判据（文档 §4.1）：只统计“收敛特征”中出现在至少一半窗口样本里的特征的最大 CV；
 * 全部特征统计照常存储，sample_count 为该特征在窗口内的原始样本数。
 */
class BaselineConvergenceTest {
    private static final long T0 = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();
    private static final long HOUR = Duration.ofHours(1).toMillis();

    /** 断路器学习池：触头磨损率恒为 0.00（CV 无收敛意义）不得阻挡锁定。 */
    @Test
    void breakerPoolWithZeroContactWearLocksActive() {
        var rig = rig();
        var point = point(ModalityTypes.SWITCHGEAR_EVENT);
        var plugin = breaker();
        BaselineSnapshot snapshot = null;
        for (int i = 0; i < 200; i++) {
            Map<String, Double> features = new LinkedHashMap<>();
            features.put("trip_duration_ms", 40.0 + (i % 5) * 0.05);
            features.put("peak_coil_current_a", 5.0 + (i % 3) * 0.02);
            features.put("contact_wear_ratio", 0.00);
            snapshot = rig.service().observe(point, envelope(ModalityTypes.SWITCHGEAR_EVENT, T0 + i * HOUR, features), plugin);
        }

        assertEquals("ACTIVE", snapshot.status(), "磨损率恒为 0 的学习池应锁定");
        assertEquals(BaselineService.ACTIVE, point.getBaselineStatus());
        var wear = rig.lastState("contact_wear_ratio");
        assertNotNull(wear, "不参与收敛判据的特征统计仍照常存储");
        assertEquals(0.0, wear.getP50(), 1e-12);
        assertEquals(200, wear.getSampleCount());
    }

    /** 断路器学习池：触头磨损率单调累积（0.00 → 0.20，CV≈0.58）同样不得让通道永久 DIVERGED。 */
    @Test
    void breakerPoolWithMonotonicContactWearLocksActive() {
        var rig = rig();
        var point = point(ModalityTypes.SWITCHGEAR_EVENT);
        var plugin = breaker();
        BaselineSnapshot snapshot = null;
        for (int i = 0; i < 200; i++) {
            Map<String, Double> features = new LinkedHashMap<>();
            features.put("trip_duration_ms", 40.0 + (i % 5) * 0.05);
            features.put("peak_coil_current_a", 5.0 + (i % 3) * 0.02);
            features.put("contact_wear_ratio", 0.001 * i);
            snapshot = rig.service().observe(point, envelope(ModalityTypes.SWITCHGEAR_EVENT, T0 + i * HOUR, features), plugin);
        }

        assertEquals("ACTIVE", snapshot.status(), "单调累积的磨损率不参与锁定门槛: CV=" + rig.lastState("contact_wear_ratio").getCv());
        assertTrue(rig.lastState("contact_wear_ratio").getCv() > 0.15, "该特征自身的 CV 确实超标");
        assertEquals(40.1, rig.lastState("trip_duration_ms").getP50(), 0.05,
                "40.0/40.05/40.1/40.15/40.2 循环取值的中位数 = 40.1");
        assertEquals(200, rig.lastState("trip_duration_ms").getSampleCount());
    }

    /** 调节阀学习池：超声泄漏 RMS 噪声大（CV≈0.5，仅按绝对阈值评分）不得阻挡锁定。 */
    @Test
    void valvePoolWithNoisyLeakRmsLocksActive() {
        var rig = rig();
        var point = point(ModalityTypes.CONTROL_VALVE_PROCESS);
        var plugin = valve();
        Random random = new Random(11L);
        BaselineSnapshot snapshot = null;
        // 200 个样本 × 18 秒 = 3600 秒，恰好覆盖 1 小时学习窗口（跨度 + 平均间隔 ≥ 窗口）
        for (int i = 0; i < 200; i++) {
            Map<String, Double> features = new LinkedHashMap<>();
            features.put("hysteresis_deadband_pct", 1.0 + (i % 4) * 0.01);
            features.put("travel_time_lag_ms", 400.0 + (i % 3) * 1.0);
            features.put("friction_force_kn", 1.25 + (i % 2) * 0.005);
            features.put("ultrasonic_leak_rms", 0.02 + 0.17 * random.nextDouble());
            snapshot = rig.service().observe(point, envelope(ModalityTypes.CONTROL_VALVE_PROCESS,
                    T0 + i * 18_000L, features), plugin);
        }

        assertEquals("ACTIVE", snapshot.status(), "泄漏 RMS 噪声不得阻挡锁定");
        assertTrue(rig.lastState("ultrasonic_leak_rms").getCv() > 0.15, "泄漏 RMS 自身的 CV 确实超标");
        assertNotNull(rig.lastState("ultrasonic_leak_rms").getP50(), "泄漏 RMS 基线仍照常学习");
        assertEquals(1.015, rig.lastState("hysteresis_deadband_pct").getP50(), 1e-9);
    }

    /** 少数样本才出现的可选收敛特征（合闸速度仅 20% 样本携带且剧烈波动）不能凭少量观测否决锁定。 */
    @Test
    void sparseOptionalFeatureCannotVetoTheLock() {
        var rig = rig();
        var point = point(ModalityTypes.SWITCHGEAR_EVENT);
        var plugin = breaker();
        BaselineSnapshot snapshot = null;
        for (int i = 0; i < 200; i++) {
            Map<String, Double> features = new LinkedHashMap<>();
            features.put("trip_duration_ms", 40.0 + (i % 5) * 0.05);
            features.put("peak_coil_current_a", 5.0 + (i % 3) * 0.02);
            if (i % 5 == 0) features.put("closing_velocity_mps", i % 10 == 0 ? 1.0 : 5.0);
            snapshot = rig.service().observe(point, envelope(ModalityTypes.SWITCHGEAR_EVENT, T0 + i * HOUR, features), plugin);
        }

        assertEquals("ACTIVE", snapshot.status(), "仅 40/200 样本携带的可选特征不得否决锁定");
        assertEquals(40, rig.lastState("closing_velocity_mps").getSampleCount(), "sample_count 为该特征的原始样本数");
        assertEquals(200, rig.lastState("trip_duration_ms").getSampleCount());
        assertTrue(rig.lastState("closing_velocity_mps").getCv() > 0.15);
    }

    /** 回归保护：出现在全部样本里的收敛特征波动剧烈时仍判 DIVERGED（收敛判据本身没有被削弱）。 */
    @Test
    void noisyEverPresentConvergenceFeatureStillDiverges() {
        var rig = rig();
        var point = point(ModalityTypes.SWITCHGEAR_EVENT);
        var plugin = breaker();
        BaselineSnapshot snapshot = null;
        for (int i = 0; i < 200; i++) {
            Map<String, Double> features = new LinkedHashMap<>();
            features.put("trip_duration_ms", 40.0 + (i % 5) * 0.05);
            features.put("peak_coil_current_a", 5.0 + (i % 3) * 0.02);
            features.put("closing_velocity_mps", i % 2 == 0 ? 1.0 : 5.0);
            snapshot = rig.service().observe(point, envelope(ModalityTypes.SWITCHGEAR_EVENT, T0 + i * HOUR, features), plugin);
        }

        assertEquals("DIVERGED", snapshot.status(), "1.0/5.0 交替的合闸速度 CV≈0.67，应判发散");
        assertEquals("BASELINE_DIVERGENCE", snapshot.warning());
        assertEquals(BaselineService.DIVERGED, point.getBaselineStatus());
        assertTrue(point.getBaselineCv() > BaselineService.CV_LOCK_THRESHOLD);
    }

    // ---------- 测试装配 ----------

    private record Rig(BaselineService service, List<BaselineFeatureStateEntity> states) {
        BaselineFeatureStateEntity lastState(String code) {
            for (int i = states.size() - 1; i >= 0; i--)
                if (code.equals(states.get(i).getFeatureCode())) return states.get(i);
            return null;
        }
    }

    /** 内存仓储桩：特征状态行与按模态样本均落在列表里，窗口查询按时间过滤。 */
    private static Rig rig() {
        List<BaselineFeatureStateEntity> states = new ArrayList<>();
        var featureStates = mock(BaselineFeatureStateRepository.class);
        when(featureStates.findByPoint_IdAndModalityTypeAndFeatureCode(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(featureStates.findByPoint_IdAndModalityType(any(), anyString())).thenReturn(List.of());
        when(featureStates.save(any())).thenAnswer(inv -> { states.add(inv.getArgument(0)); return inv.getArgument(0); });

        List<ModalityBaselineSampleEntity> samples = new ArrayList<>();
        var modalitySamples = mock(ModalityBaselineSampleRepository.class);
        when(modalitySamples.existsByPoint_IdAndModalityTypeAndSampledAt(any(), anyString(), any()))
                .thenAnswer(inv -> samples.stream().anyMatch(s -> s.getSampledAt().equals(inv.getArgument(2))));
        when(modalitySamples.save(any())).thenAnswer(inv -> { samples.add(inv.getArgument(0)); return inv.getArgument(0); });
        when(modalitySamples.findByPoint_IdAndModalityTypeAndSampledAtBetweenOrderBySampledAtAsc(
                any(), anyString(), any(), any()))
                .thenAnswer(inv -> samples.stream()
                        .filter(s -> !s.getSampledAt().isBefore(inv.getArgument(2)) && !s.getSampledAt().isAfter(inv.getArgument(3)))
                        .toList());

        var service = new BaselineService(mock(PointRepository.class), mock(DeviceRepository.class),
                mock(BaselineSampleRepository.class), featureStates, modalitySamples, mock(com.pdm.mvp.analytics.SpectrumBaselineService.class), Duration.ofMinutes(5));
        return new Rig(service, states);
    }

    private static PointEntity point(String modality) {
        var device = new DeviceEntity(new FactoryEntity("FACT_01", "一号工厂"), "DEVICE_01", "测试设备", null, null, null);
        PointEntity point = new PointEntity(device, "CHANNEL", "测试通道", null);
        point.setModalityType(modality);
        return point;
    }

    private static TelemetryEnvelope envelope(String modality, long timestamp, Map<String, Double> features) {
        return new TelemetryEnvelope(ModalityTypes.SUPPORTED_CONTRACT_VERSION, modality, timestamp, "FACT_01", "DEVICE_01",
                "CHANNEL", 0, new OperatingCondition(null, null, true), features, null, null);
    }

    private static CircuitBreakerPlugin breaker() {
        return new CircuitBreakerPlugin(new SwitchgearProperties(true, 1.10, 1.20, 0.80, Duration.ofDays(30),
                Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(30)));
    }

    private static ControlValvePlugin valve() {
        return new ControlValvePlugin(new ControlValveProperties(true, 2.5, 5.0, 0.2, 0.5, Duration.ofHours(1)),
                Duration.ofHours(24));
    }
}
