package com.pdm.mvp.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.DeviceRepository;
import com.pdm.mvp.asset.DeviceTypeEntity;
import com.pdm.mvp.asset.DeviceTypeService;
import com.pdm.mvp.asset.FactoryEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.diagnostics.DiagnosticEvaluator;
import com.pdm.mvp.diagnostics.DiagnosticRuleRepository;
import com.pdm.mvp.modality.BaselineSnapshot;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.telemetry.OperatingCondition;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import com.pdm.mvp.telemetry.VibrationContractAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 振动基线学习对可选 a_peak 的兼容：协议 2.1.0 旋转振动样本可不带 a_peak，
 * 学习不得因拆箱失败而 500；带与不带峰值的窗口都能锁定，峰值基线只在窗口内存在峰值时写入。
 */
class VibrationBaselineOptionalPeakTest {
    private static final long T0 = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();

    @Test
    void windowWithPeakValuesLearnsPeakBaselineAsBefore() {
        var rig = new Rig();
        for (int i = 0; i < 200; i++)
            rig.service.observe(rig.point, envelope(T0 + i * 1500L, 2.0 + (i % 4) * 0.02), rig.plugin);

        assertEquals(BaselineService.ACTIVE, rig.point.getBaselineStatus());
        assertEquals(2.03, rig.point.getABasePeak(), 1e-9, "a_base_peak = 清洗后峰值中位数");
        assertTrue(rig.samples.stream().allMatch(sample -> sample.getAPeak() != null));
        assertEquals(2.03, rig.service.snapshotFromPoint(rig.point, null).featureBase().get(VibrationContractAdapter.A_PEAK), 1e-9);
    }

    @Test
    void windowWithoutPeakValuesLocksAndLeavesPeakBaselineNull() {
        var rig = new Rig();
        BaselineSnapshot snapshot = null;
        for (int i = 0; i < 200; i++)
            snapshot = rig.service.observe(rig.point, envelope(T0 + i * 1500L, null), rig.plugin);

        assertEquals("ACTIVE", snapshot.status(), "缺省 a_peak 不得让基线学习失败");
        assertEquals(BaselineService.ACTIVE, rig.point.getBaselineStatus());
        assertNull(rig.point.getABasePeak(), "窗口内没有任何峰值时不写 a_base_peak");
        assertNull(snapshot.featureBase().get(VibrationContractAdapter.A_PEAK));
        assertEquals(0.5, snapshot.featureBase().get(VibrationContractAdapter.FREQ_1X_AMP), 1e-9, "其余特征基线照常锁定");
        assertTrue(rig.samples.stream().allMatch(sample -> sample.getAPeak() == null));
        assertFalse(rig.states.stream().anyMatch(state -> VibrationContractAdapter.A_PEAK.equals(state.getFeatureCode())),
                "无峰值样本时不写 a_peak 特征状态行");
    }

    @Test
    void mixedWindowDerivesPeakBaselineFromTheSamplesThatCarryIt() {
        var rig = new Rig();
        for (int i = 0; i < 200; i++)
            rig.service.observe(rig.point, envelope(T0 + i * 1500L, i % 2 == 0 ? 2.0 + (i % 4) * 0.02 : null), rig.plugin);

        assertEquals(BaselineService.ACTIVE, rig.point.getBaselineStatus());
        assertEquals(2.02, rig.point.getABasePeak(), 1e-9, "只有一半样本带峰值时，取这些样本的中位数");
        var peakState = rig.states.stream()
                .filter(state -> VibrationContractAdapter.A_PEAK.equals(state.getFeatureCode())).findFirst().orElseThrow();
        assertEquals(200, peakState.getSampleCount(), "振动双写保持旧语义：sample_count = 窗口样本数");
    }

    /** 共享评分链路对缺省峰值不敏感：诊断评估把缺失的 a_peak 视为未观测，而不是抛异常。 */
    @Test
    void scoringChainToleratesMissingPeak() {
        var rig = new Rig();
        for (int i = 0; i < 200; i++)
            rig.service.observe(rig.point, envelope(T0 + i * 1500L, null), rig.plugin);
        var baseline = rig.service.snapshotFromPoint(rig.point, null);
        var sample = envelope(T0 + 200 * 1500L, null);

        var assessment = rig.plugin.score(rig.point, sample, baseline, rig.plugin.diagnose(rig.point, sample, baseline));

        assertEquals(100.0, assessment.healthIndex(), 1e-9);
        assertEquals("HEALTHY", assessment.level());
        assertFalse(assessment.hardLimitBreached());
    }

    /** 工况门禁：显式 is_steady=false 判不可评估；旧振动载荷不带该标志（null）与 is_steady=true 保持可评估。 */
    @Test
    void steadyFlagGatesExplicitlyUnsteadySamplesOnly() {
        var rejected = new TelemetryEnvelope(2, ModalityTypes.ROTATING_VIB, T0, "FACT_01", "D", "P", 0,
                new OperatingCondition(1500.0, null, false), coreFeatures(null), null, null);
        var gate = riglessPlugin().gate(rejected);
        assertFalse(gate.assessable());
        assertEquals(ModalityTypes.CONDITION_REJECTED, gate.status());
        assertEquals(ModalityTypes.CONDITION_REJECTED_CODE, gate.statusCode());
        assertFalse(gate.stopped(), "非稳态不是停机");

        var legacy = new TelemetryEnvelope(2, ModalityTypes.ROTATING_VIB, T0, "FACT_01", "D", "P", 0,
                new OperatingCondition(1500.0, null, null), coreFeatures(null), null, null);
        assertTrue(riglessPlugin().gate(legacy).assessable(), "旧振动载荷不带稳态标志，行为不变");
        var steady = new TelemetryEnvelope(2, ModalityTypes.ROTATING_VIB, T0, "FACT_01", "D", "P", 0,
                new OperatingCondition(1500.0, null, true), coreFeatures(null), null, null);
        assertTrue(riglessPlugin().gate(steady).assessable());
        var noCondition = new TelemetryEnvelope(2, ModalityTypes.ROTATING_VIB, T0, "FACT_01", "D", "P", 0,
                null, coreFeatures(1.0), null, null);
        assertTrue(riglessPlugin().gate(noCondition).assessable());

        var coasting = new TelemetryEnvelope(2, ModalityTypes.ROTATING_VIB, T0, "FACT_01", "D", "P", 0,
                new OperatingCondition(1500.0, null, false),
                Map.of(VibrationContractAdapter.V_RMS, 0.01, VibrationContractAdapter.A_PEAK, 0.2,
                        VibrationContractAdapter.A_KURTOSIS, 3.0, VibrationContractAdapter.FREQ_1X_AMP, 0.5,
                        VibrationContractAdapter.FREQ_2X_AMP, 0.2),
                null, null);
        assertEquals("STOPPED", riglessPlugin().gate(coasting).status(), "停机判据优先于稳态标志");
    }

    private static RotatingVibrationPlugin riglessPlugin() {
        var deviceTypes = mock(DeviceTypeService.class);
        var general = mock(DeviceTypeEntity.class);
        when(general.getId()).thenReturn(1L);
        when(general.getTypeName()).thenReturn("通用设备");
        when(deviceTypes.general()).thenReturn(general);
        return new RotatingVibrationPlugin(new HealthIndexCalculator(),
                new DiagnosticEvaluator(mock(DiagnosticRuleRepository.class), deviceTypes),
                new DegradationTracker(Duration.ofHours(24)));
    }

    private static Map<String, Double> coreFeatures(Double aPeak) {
        Map<String, Double> features = new LinkedHashMap<>();
        features.put(VibrationContractAdapter.V_RMS, 1.0);
        features.put(VibrationContractAdapter.A_KURTOSIS, 3.0);
        features.put(VibrationContractAdapter.FREQ_1X_AMP, 0.5);
        features.put(VibrationContractAdapter.FREQ_2X_AMP, 0.2);
        features.put(VibrationContractAdapter.ENVELOPE_BPFO_AMP, 0.01);
        if (aPeak != null) features.put(VibrationContractAdapter.A_PEAK, aPeak);
        return features;
    }

    /** 200 个样本 × 1.5 秒 = 300 秒，恰好覆盖 5 分钟学习窗口（跨度 + 平均间隔 ≥ 窗口）。 */
    private static TelemetryEnvelope envelope(long timestamp, Double aPeak) {
        return new TelemetryEnvelope(2, ModalityTypes.ROTATING_VIB, timestamp, "FACT_01", "PUMP_P102A", "DE_RADIAL", 0,
                new OperatingCondition(1500.0, null, null), coreFeatures(aPeak), null, null);
    }

    /** 内存仓储桩：振动基线样本与特征状态行落在列表里。 */
    private static final class Rig {
        final PointEntity point;
        final List<BaselineSampleEntity> samples = new ArrayList<>();
        final List<BaselineFeatureStateEntity> states = new ArrayList<>();
        final BaselineService service;
        final RotatingVibrationPlugin plugin = riglessPlugin();

        Rig() {
            var device = new DeviceEntity(new FactoryEntity("FACT_01", "一号工厂"), "PUMP_P102A", "循环水泵", null, null, null);
            point = new PointEntity(device, "DE_RADIAL", "驱动端水平", null);
            point.setModalityType(ModalityTypes.ROTATING_VIB);

            var sampleRepository = mock(BaselineSampleRepository.class);
            when(sampleRepository.existsByPoint_IdAndSampledAt(any(), any()))
                    .thenAnswer(inv -> samples.stream().anyMatch(s -> s.getSampledAt().equals(inv.getArgument(1))));
            when(sampleRepository.save(any())).thenAnswer(inv -> { samples.add(inv.getArgument(0)); return inv.getArgument(0); });
            when(sampleRepository.findByPoint_IdAndSampledAtBetweenOrderBySampledAtAsc(any(), any(), any()))
                    .thenAnswer(inv -> samples.stream()
                            .filter(s -> !s.getSampledAt().isBefore(inv.getArgument(1)) && !s.getSampledAt().isAfter(inv.getArgument(2)))
                            .toList());

            var featureStates = mock(BaselineFeatureStateRepository.class);
            when(featureStates.findByPoint_IdAndModalityTypeAndFeatureCode(any(), any(), any())).thenReturn(java.util.Optional.empty());
            when(featureStates.findByPoint_IdAndModalityType(any(), any())).thenReturn(List.of());
            when(featureStates.save(any())).thenAnswer(inv -> { states.add(inv.getArgument(0)); return inv.getArgument(0); });

            service = new BaselineService(mock(PointRepository.class), mock(DeviceRepository.class), sampleRepository,
                    featureStates, mock(ModalityBaselineSampleRepository.class), mock(com.pdm.mvp.analytics.SpectrumBaselineService.class), Duration.ofMinutes(5));
        }
    }
}
