package com.pdm.mvp.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pdm.mvp.alert.AlertService;
import com.pdm.mvp.analytics.AnalyticsEngine;
import com.pdm.mvp.analytics.BaselineService;
import com.pdm.mvp.analytics.DegradationTracker;
import com.pdm.mvp.analytics.HealthIndexCalculator;
import com.pdm.mvp.analytics.RotatingVibrationPlugin;
import com.pdm.mvp.analytics.SpikeFilter;
import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.FactoryEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.controlvalve.ControlValvePlugin;
import com.pdm.mvp.controlvalve.ControlValveProperties;
import com.pdm.mvp.mcsa.McsaPlugin;
import com.pdm.mvp.mcsa.McsaProperties;
import com.pdm.mvp.modality.BaselineSnapshot;
import com.pdm.mvp.modality.ModalityPlugin;
import com.pdm.mvp.modality.ModalityPluginRegistry;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.switchgear.CircuitBreakerPlugin;
import com.pdm.mvp.switchgear.SwitchgearProperties;
import com.pdm.mvp.timeseries.TelemetryStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 遥测接入编排（TelemetryService.accept(TelemetryEnvelope, source)）驱动真实插件链路：
 * 工况门禁、尖峰缓冲、告警开关、事件型标志传递、模态匹配与去重。
 */
class TelemetryServicePipelineTest {
    private static final Instant NOW = Instant.parse("2026-11-23T03:00:00Z");
    private static final long NOW_MILLIS = NOW.toEpochMilli();

    private final AssetLookup assets = mock(AssetLookup.class);
    private final TelemetryStore store = mock(TelemetryStore.class);
    private final AnalyticsEngine analytics = mock(AnalyticsEngine.class);
    private final PointRepository points = mock(PointRepository.class);
    private final AlertService alerts = mock(AlertService.class);
    private final ModalityPluginRegistry plugins = new ModalityPluginRegistry(List.of(
            vibrationPlugin(), mcsaPlugin(false), breakerPlugin(), valvePlugin()));
    private final TelemetryService service = new TelemetryService(assets,
            new TelemetryValidator(Clock.fixed(NOW, ZoneOffset.UTC)), store, analytics, points, alerts, plugins);

    /** 非稳态调节阀样本：门禁拒绝，分析状态与防抖窗口清空，不写告警。 */
    @Test
    void nonSteadyValveSampleIsRejectedByGateAndResetsStateWithoutAlerting() {
        PointEntity point = point("FCV101", "LOOP", ModalityTypes.CONTROL_VALVE_PROCESS);

        var result = service.accept(valveEnvelope(NOW_MILLIS, 3.5, false), "REST");

        assertEquals("CONDITION_REJECTED", result.status());
        assertNull(result.healthIndex());
        verify(analytics).reset(point.getId());
        verify(alerts).resetDebounce(point.getId());
        verify(analytics, never()).observe(any(), any(), any());
        verify(alerts, never()).observe(any(), any(), any(), anyDouble(), anyDouble(), anyBoolean());
        verify(store).write(argThat(sample -> "CONDITION_REJECTED".equals(sample.status()) && sample.statusCode() == 6
                && sample.healthIndex() == null));
    }

    /** 事件型（断路器）样本不经 3 点尖峰缓冲：分析内核一返回评估结果即进入告警链路（带事件型标志）。 */
    @Test
    void breakerSampleIsNeverBufferedBySpikeFilterAndIsScoredImmediately() {
        PointEntity point = point("BKR_10KV_02", "TRIP_UNIT", ModalityTypes.SWITCHGEAR_EVENT);
        var baseline = new BaselineSnapshot(point.getId(), ModalityTypes.SWITCHGEAR_EVENT, "ACTIVE", 200, Instant.now(),
                null, Map.of("trip_duration_ms", 40.0, "peak_coil_current_a", 5.0), Map.of());
        var live = serviceWithRealEngine(activeBaseline(baseline));

        var result = live.accept(breakerEnvelope(NOW_MILLIS, 42.5), "REST");

        assertEquals("HEALTHY", result.status(), "首个事件样本即评分，不进入 ANALYSIS_BUFFERING");
        assertEquals(100.0, result.healthIndex(), 1e-9);
        verify(alerts).observe(any(), argThat(sample -> sample.feature("trip_duration_ms") == 42.5), any(),
                eq(42.5), eq(40.0), eq(true));
    }

    /** 连续型（调节阀）样本：前两个样本被尖峰缓冲，第三个样本到达后才评分一次并带连续型标志进入告警链路。 */
    @Test
    void valveFirstSamplesAreBufferedBySpikeFilter() {
        PointEntity point = point("FCV101", "LOOP", ModalityTypes.CONTROL_VALVE_PROCESS);
        var baseline = new BaselineSnapshot(point.getId(), ModalityTypes.CONTROL_VALVE_PROCESS, "ACTIVE", 200,
                Instant.now(), null, Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04), Map.of());
        var live = serviceWithRealEngine(activeBaseline(baseline));

        assertEquals("ANALYSIS_BUFFERING", live.accept(valveEnvelope(NOW_MILLIS, 1.0, true), "REST").status());
        assertEquals("ANALYSIS_BUFFERING", live.accept(valveEnvelope(NOW_MILLIS + 1000, 1.0, true), "REST").status());
        verifyNoInteractions(alerts);

        var scored = live.accept(valveEnvelope(NOW_MILLIS + 2000, 1.0, true), "REST");
        assertEquals("HEALTHY", scored.status());
        verify(alerts).observe(any(), argThat(sample -> sample.timestamp() == NOW_MILLIS + 1000), any(),
                eq(1.0), eq(1.0), eq(false));
    }

    /** 插件关闭告警（MCSA 默认未验收）：照常评分入库，但绝不进入告警链路。 */
    @Test
    void alertsDisabledPluginNeverReachesAlertService() {
        point("MOTOR_M01", "CURRENT", ModalityTypes.MCSA);
        var live = serviceWithRealEngine(activeBaseline(null));

        TelemetryResult result = null;
        for (int i = 0; i < 3; i++) result = live.accept(mcsaEnvelope(NOW_MILLIS + i * 1000), "REST");

        assertEquals("HEALTHY", result.status(), "第 3 个样本（3 点窗口中心）完成评分");
        assertEquals(100.0, result.healthIndex(), 1e-9);
        verifyNoInteractions(alerts);
    }

    /** 测点模态与遥测模态不一致：400，且不触碰分析/告警/时序写入。 */
    @Test
    void modalityMismatchIsRejectedBeforeAnyProcessing() {
        point("FCV101", "LOOP", ModalityTypes.CONTROL_VALVE_PROCESS);

        ResponseStatusException exception = org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
                () -> service.accept(new TelemetryEnvelope(2, ModalityTypes.ROTATING_VIB, NOW_MILLIS, "FACT_01", "FCV101",
                        "LOOP", 0, null, Map.of("v_rms", 1.0, "a_kurtosis", 3.0, "freq_1x_amp", 0.5, "freq_2x_amp", 0.2),
                        null, null), "REST"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("测点模态与遥测模态不匹配", exception.getReason());
        verifyNoInteractions(analytics, alerts, store);
    }

    /** 同一时间戳重复上报与乱序：直接判 DUPLICATE_OR_LATE_IGNORED，不重复分析、不重复写入。 */
    @Test
    void duplicateOrOutOfOrderSampleIsIgnored() {
        PointEntity point = point("FCV101", "LOOP", ModalityTypes.CONTROL_VALVE_PROCESS);

        var first = service.accept(valveEnvelope(NOW_MILLIS + 2000, 1.0, true), "REST");
        var duplicate = service.accept(valveEnvelope(NOW_MILLIS + 2000, 1.0, true), "REST");
        var late = service.accept(valveEnvelope(NOW_MILLIS + 1000, 1.0, true), "REST");

        assertEquals("ANALYSIS_BUFFERING", first.status());
        assertEquals("DUPLICATE_OR_LATE_IGNORED", duplicate.status());
        assertEquals("DUPLICATE_OR_LATE_IGNORED", late.status());
        verify(analytics).observe(eq(point), any(), any());
        verify(store).write(argThat(sample -> sample.timestamp().toEpochMilli() == NOW_MILLIS + 2000));
        verify(store, org.mockito.Mockito.times(1)).write(any());
        verifyNoInteractions(alerts);
    }

    // ---------- 测试装配 ----------

    private TelemetryService serviceWithRealEngine(BaselineService baselines) {
        return new TelemetryService(assets, new TelemetryValidator(Clock.fixed(NOW, ZoneOffset.UTC)), store,
                new AnalyticsEngine(new SpikeFilter(), baselines, plugins), points, alerts, plugins);
    }

    /** 基线桩：任何样本都返回 ACTIVE 快照（hasAll 由快照里的特征保证），让真实 SpikeFilter 与插件链路跑到评分。 */
    private BaselineService activeBaseline(BaselineSnapshot snapshot) {
        BaselineService baselines = mock(BaselineService.class);
        when(baselines.observe(any(), any(), any())).thenAnswer(inv -> {
            PointEntity point = inv.getArgument(0);
            TelemetryEnvelope sample = inv.getArgument(1);
            ModalityPlugin plugin = inv.getArgument(2);
            if (snapshot != null && snapshot.modalityType().equals(plugin.modalityType())) return snapshot;
            return new BaselineSnapshot(point.getId(), plugin.modalityType(), "ACTIVE", 200, Instant.now(), null,
                    Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04, "sideband_ratio", 0.001,
                            "carrier_amp_db", -33.0),
                    Map.of());
        });
        return baselines;
    }

    /** 测点挂在带设备类型的设备上（诊断归因需要设备类型；测点未持久化，id 为 null）。 */
    private PointEntity point(String deviceCode, String pointCode, String modality) {
        var type = mock(com.pdm.mvp.asset.DeviceTypeEntity.class);
        when(type.getId()).thenReturn(1L);
        when(type.getTypeName()).thenReturn("通用设备");
        var device = new DeviceEntity(new FactoryEntity("FACT_01", "一号工厂"), deviceCode, "测试设备", null, null, type);
        PointEntity point = new PointEntity(device, pointCode, "测试测点", null);
        point.setModalityType(modality);
        when(assets.requirePoint(any(), any(), any())).thenReturn(point);
        return point;
    }

    private static TelemetryEnvelope mcsaEnvelope(long timestamp) {
        return new TelemetryEnvelope(2, ModalityTypes.MCSA, timestamp, "FACT_01", "MOTOR_M01", "CURRENT", 0,
                new OperatingCondition(1440.0, 75.0, true),
                Map.of("mains_freq_hz", 50.0, "slip_pct", 2.5, "load_pct", 75.0,
                        "sideband_amp_db", -93.0, "carrier_amp_db", -33.0),
                null, null);
    }

    private static TelemetryEnvelope valveEnvelope(long timestamp, double deadband, boolean steady) {
        return new TelemetryEnvelope(2, ModalityTypes.CONTROL_VALVE_PROCESS, timestamp, "FACT_01", "FCV101", "LOOP", 0,
                new OperatingCondition(null, null, steady),
                Map.of("hysteresis_deadband_pct", deadband, "ultrasonic_leak_rms", 0.04), null, null);
    }

    private static TelemetryEnvelope breakerEnvelope(long timestamp, double tripMs) {
        return new TelemetryEnvelope(2, ModalityTypes.SWITCHGEAR_EVENT, timestamp, "FACT_01", "BKR_10KV_02", "TRIP_UNIT",
                0, null, Map.of("trip_duration_ms", tripMs, "peak_coil_current_a", 5.0), null, null);
    }

    private static RotatingVibrationPlugin vibrationPlugin() {
        return new RotatingVibrationPlugin(new HealthIndexCalculator(), null,
                new DegradationTracker(Duration.ofHours(24)));
    }

    private static McsaPlugin mcsaPlugin(boolean alertsEnabled) {
        return new McsaPlugin(new McsaProperties(alertsEnabled, 20, 100, 15, 54, 40), null);
    }

    private static CircuitBreakerPlugin breakerPlugin() {
        return new CircuitBreakerPlugin(new SwitchgearProperties(true, 1.10, 1.20, 0.80, Duration.ofDays(30),
                Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(30)));
    }

    private static ControlValvePlugin valvePlugin() {
        return new ControlValvePlugin(new ControlValveProperties(true, 2.5, 5.0, 0.2, 0.5, Duration.ofHours(1)),
                Duration.ofHours(24));
    }
}
