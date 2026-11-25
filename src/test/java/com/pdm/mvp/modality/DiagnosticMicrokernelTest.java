package com.pdm.mvp.modality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pdm.mvp.alert.DeviceHealthWindow;
import com.pdm.mvp.analytics.BaselineFeatureStateEntity;
import com.pdm.mvp.analytics.BaselineFeatureStateRepository;
import com.pdm.mvp.analytics.BaselineSampleRepository;
import com.pdm.mvp.analytics.BaselineService;
import com.pdm.mvp.analytics.HealthAssessment;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.DeviceRepository;
import com.pdm.mvp.asset.FactoryEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.controlvalve.ControlValvePlugin;
import com.pdm.mvp.controlvalve.ControlValveProperties;
import com.pdm.mvp.telemetry.OperatingCondition;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 微内核调度引擎（文档 §4.4）验证：门禁 STANDBY 出口、NORMAL 档映射、
 * 按节律分流的防抖判定与硬限击穿直通；另附 windowed 学习窗口覆盖检查。
 */
class DiagnosticMicrokernelTest {
    private static final long NOW = 1_780_000_000_000L;
    private static final long DAY = Duration.ofDays(1).toMillis();

    private final DeviceHealthWindow healthWindow = new DeviceHealthWindow();
    private final DiagnosticMicrokernel kernel = new DiagnosticMicrokernel(healthWindow);
    private final ControlValvePlugin valve = new ControlValvePlugin(new ControlValveProperties(
            true, 2.5, 5.0, 0.2, 0.5, Duration.ofHours(1)), Duration.ofHours(24));
    private final BaselineSnapshot valveBaseline = new BaselineSnapshot(12L, "CONTROL_VALVE_PROCESS", "ACTIVE",
            200, Instant.now(), null, Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04), Map.of());

    /** 门禁未通过：返回 STANDBY 出口（满分、不触发、gated=true），归因携带门禁消息。 */
    @Test
    void gatedSampleReturnsStandbyOutcome() {
        var unsteady = valveEnvelope(NOW, 3.5, 0.04, new OperatingCondition(null, null, false));
        var outcome = kernel.execute(point(), 1L, valve, unsteady, valveBaseline);
        assertEquals(100.0, outcome.finalHealthScore(), 0.001);
        assertEquals("STANDBY", outcome.alarmLevel());
        assertEquals("非稳态工况，暂不可评估", outcome.defectAttribution());
        assertFalse(outcome.shouldTriggerAlarm());
        assertFalse(outcome.hardLimitBreached());
        assertTrue(outcome.gated());
    }

    /** 健康样本：文档命名报警档位 NORMAL（HI ≥ 70），单样本不触发告警。 */
    @Test
    void healthySampleReportsNormalAlarmLevel() {
        var healthy = valveEnvelope(NOW, 1.0, 0.04, new OperatingCondition(null, null, true));
        var outcome = kernel.execute(point(), 1L, valve, healthy, valveBaseline);
        assertEquals(100.0, outcome.finalHealthScore(), 0.001);
        assertEquals("NORMAL", outcome.alarmLevel());
        assertFalse(outcome.gated());
        assertFalse(outcome.shouldTriggerAlarm());
    }

    /** 防抖按节律分流：事件型跨天窗口保留（第 5 次触发），连续型跨天清窗（永不触发）。 */
    @Test
    void debounceFollowsPluginCadence() {
        var low = new HealthAssessment(40.0, 40.0, 40.0, "WARNING", 2, null, 0.0, 1.0, List.of(), false);
        long t0 = 1_000_000L;
        for (int i = 0; i < 4; i++)
            assertFalse(kernel.shouldTriggerAlarm(1L, 1L, t0 + i * DAY, low, true), "事件型第 " + (i + 1) + " 次不触发");
        assertTrue(kernel.shouldTriggerAlarm(1L, 1L, t0 + 4 * DAY, low, true), "事件型第 5 次满足 4-of-5");

        for (int i = 0; i < 4; i++)
            assertFalse(kernel.shouldTriggerAlarm(1L, 2L, t0 + i * DAY, low, false));
        assertFalse(kernel.shouldTriggerAlarm(1L, 2L, t0 + 4 * DAY, low, false), "连续型断档清窗永不触发");
    }

    /** 硬限击穿直通：无窗口积累也立即放行。 */
    @Test
    void hardLimitBreachBypassesDebounce() {
        var breach = new HealthAssessment(25.0, 25.0, 25.0, "DANGER", 3, null, 0.0, 1.0, List.of(), true);
        assertTrue(kernel.shouldTriggerAlarm(9L, 9L, NOW, breach, false));
    }

    /** 硬限击穿样本同样计入 M-of-N 窗口：击穿 1 次 + 其后 4 次样本（含 1 次健康）即满足 4-of-5，不必多等一个样本。 */
    @Test
    void hardLimitSampleIsFedIntoDebounceWindow() {
        var breach = new HealthAssessment(30.0, 30.0, 30.0, "DANGER", 3, null, 0.0, 1.0, List.of(), true);
        var low = new HealthAssessment(40.0, 40.0, 40.0, "WARNING", 2, null, 0.0, 1.0, List.of(), false);
        var healthy = new HealthAssessment(100.0, 100.0, 100.0, "HEALTHY", 0, null, 0.0, 1.0, List.of(), false);
        long t0 = 1_000_000L;

        assertTrue(kernel.shouldTriggerAlarm(7L, 70L, t0, breach, false), "硬限击穿直通");
        assertFalse(kernel.shouldTriggerAlarm(7L, 70L, t0 + 1000, low, false));
        assertFalse(kernel.shouldTriggerAlarm(7L, 70L, t0 + 2000, healthy, false));
        assertFalse(kernel.shouldTriggerAlarm(7L, 70L, t0 + 3000, low, false), "窗口仅 4 条、3 次低分，尚不满足");
        assertTrue(kernel.shouldTriggerAlarm(7L, 70L, t0 + 4000, low, false),
                "窗口 [击穿, 低, 健康, 低, 低] 有 4 次低分，应触发（击穿样本不计入时需再等一个样本）");
    }

    /** windowed 学习策略：插件声明的 1 小时窗口未覆盖前不得锁定，覆盖后且 CV 达标即锁定。 */
    @Test
    void windowedBaselinePolicyRequiresPluginDeclaredWindowSpan() {
        PointEntity point = realValvePoint();
        var featureStates = mock(BaselineFeatureStateRepository.class);
        when(featureStates.findByPoint_IdAndModalityTypeAndFeatureCode(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        when(featureStates.findByPoint_IdAndModalityType(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        when(featureStates.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        var modalitySamples = mock(com.pdm.mvp.analytics.ModalityBaselineSampleRepository.class);
        List<com.pdm.mvp.analytics.ModalityBaselineSampleEntity> sampleStore = new ArrayList<>();
        when(modalitySamples.existsByPoint_IdAndModalityTypeAndSampledAt(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> sampleStore.stream().anyMatch(s -> s.getSampledAt().equals(inv.getArgument(2))));
        when(modalitySamples.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> { sampleStore.add(inv.getArgument(0)); return inv.getArgument(0); });
        when(modalitySamples.findByPoint_IdAndModalityTypeAndSampledAtBetweenOrderBySampledAtAsc(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> sampleStore.stream()
                        .filter(s -> !s.getSampledAt().isBefore(inv.getArgument(2))
                                && !s.getSampledAt().isAfter(inv.getArgument(3))).toList());
        var service = new BaselineService(mock(PointRepository.class), mock(DeviceRepository.class),
                mock(BaselineSampleRepository.class), featureStates, modalitySamples, mock(com.pdm.mvp.analytics.SpectrumBaselineService.class), Duration.ofMinutes(5));
        var stub = new WindowedStubPlugin();

        BaselineSnapshot snapshot = null;
        // 15s × 260 = 65min：第 240 个样本起才覆盖插件声明的 1 小时窗口
        for (int i = 0; i < 260; i++) {
            snapshot = service.observe(point, stubEnvelope(T0 + i * 15_000L), stub);
            if (i == 238) assertEquals("LEARNING", snapshot.status(), "未覆盖插件声明的窗口前不得锁定");
        }
        assertEquals("ACTIVE", snapshot.status(), "覆盖窗口且 CV 达标后应锁定");
        ArgumentCaptor<BaselineFeatureStateEntity> captor = ArgumentCaptor.forClass(BaselineFeatureStateEntity.class);
        verify(featureStates, atLeastOnce()).save(captor.capture());
        assertEquals(BaselineService.ACTIVE, captor.getValue().getStatus());
    }

    // ---------- 测试装配 ----------

    private static final long T0 = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();

    private PointEntity point() {
        PointEntity point = mock(PointEntity.class);
        when(point.getId()).thenReturn(12L);
        var device = mock(DeviceEntity.class);
        when(point.getDevice()).thenReturn(device);
        when(device.getDeviceType()).thenReturn(null);
        return point;
    }

    private PointEntity realValvePoint() {
        var factory = new FactoryEntity("FACT_01", "一号工厂");
        var device = new DeviceEntity(factory, "FCV101", "给水调节阀", null, null, null);
        PointEntity point = new PointEntity(device, "LOOP", "回环路", null);
        point.setModalityType("WINDOWED_VALVE_TEST");
        return point;
    }

    private TelemetryEnvelope valveEnvelope(long timestamp, double deadband, double leak, OperatingCondition condition) {
        return new TelemetryEnvelope(2, "CONTROL_VALVE_PROCESS", timestamp, "FACT_01", "FCV101", "LOOP",
                0, condition, Map.of("hysteresis_deadband_pct", deadband, "ultrasonic_leak_rms", leak), null, null);
    }

    private TelemetryEnvelope stubEnvelope(long timestamp) {
        return new TelemetryEnvelope(2, "WINDOWED_VALVE_TEST", timestamp, "FACT_01", "FCV101", "LOOP",
                0, new OperatingCondition(null, null, true), Map.of("deadband_test", 1.0), null, null);
    }

    /** 阀形桩插件：windowed(1h) 学习策略，恒定特征值保证 CV=0 锁定。 */
    private static final class WindowedStubPlugin extends MechanismStrategyPlugin {
        WindowedStubPlugin() { super(Duration.ofHours(24)); }
        @Override public String modalityType() { return "WINDOWED_VALVE_TEST"; }
        @Override public Set<String> requiredFeatures() { return Set.of("deadband_test"); }
        @Override public boolean isOperatingSteady(Map<String, Object> opCondition) { return true; }
        @Override public PluginDiagnosisResult processDiagnosis(Map<String, Double> currentMetrics,
                Map<String, Double> baselineMetrics, Map<String, Object> metadataConfig) {
            return new PluginDiagnosisResult(100.0, "OK", false);
        }
        @Override public BaselineLearning baselineLearning() { return BaselineLearning.windowed(Duration.ofHours(1)); }
        @Override public boolean alertsEnabled() { return false; }
        @Override protected String adviceFor(PluginDiagnosisResult result) { return "维持监测"; }
    }
}
