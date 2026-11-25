package com.pdm.mvp.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pdm.mvp.analytics.BaselineService;
import com.pdm.mvp.analytics.DegradationTracker;
import com.pdm.mvp.analytics.HealthAssessment;
import com.pdm.mvp.analytics.HealthIndexCalculator;
import com.pdm.mvp.analytics.RotatingVibrationPlugin;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.DeviceTypeEntity;
import com.pdm.mvp.asset.FactoryEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.controlvalve.ControlValvePlugin;
import com.pdm.mvp.controlvalve.ControlValveProperties;
import com.pdm.mvp.modality.BaselineSnapshot;
import com.pdm.mvp.modality.DiagnosticMicrokernel;
import com.pdm.mvp.modality.TelemetryFreshnessPolicy;
import com.pdm.mvp.modality.ModalityPluginRegistry;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.switchgear.CircuitBreakerPlugin;
import com.pdm.mvp.switchgear.SwitchgearProperties;
import com.pdm.mvp.telemetry.OperatingCondition;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import com.pdm.mvp.workorder.WorkOrderEntity;
import com.pdm.mvp.workorder.WorkOrderRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 告警链路对机理策略诊断的适配：硬限击穿判 DANGER、规则 ID 为空的诊断可正常合并/建单/入通知队列，
 * 告警视图携带测点模态与触发特征编码。
 */
class AlertServiceTest {
    private static final long NOW = Instant.parse("2026-11-23T03:00:00Z").toEpochMilli();
    private static final Map<String, Double> VALVE_BASE = Map.of("hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04);

    private final DeviceHealthWindow healthWindow = new DeviceHealthWindow();
    private final AlertRepository alerts = mock(AlertRepository.class);
    private final WorkOrderRepository workOrders = mock(WorkOrderRepository.class);
    private final PointRepository points = mock(PointRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final TelemetryFreshnessPolicy freshness = mock(TelemetryFreshnessPolicy.class);
    private final ControlValvePlugin valve = new ControlValvePlugin(new ControlValveProperties(true, 2.5, 5.0, 0.2, 0.5,
            Duration.ofHours(1)), Duration.ofHours(24));
    private final ModalityPluginRegistry plugins = new ModalityPluginRegistry(List.of(vibrationPlugin(), valve, breakerPlugin()));

    /** 调节阀超声泄漏击穿硬限：静态分恰为 30.0（设备综合 HI 不低于 30），按 HI 分档只会得到 WARNING，必须按硬限判 DANGER。 */
    @Test
    void valveHardLimitBreachRaisesDangerAlertWithWorkOrderAndNotification() {
        PointEntity point = valvePoint(30.0);
        var baseline = new BaselineSnapshot(12L, ModalityTypes.CONTROL_VALVE_PROCESS, "ACTIVE", 200, Instant.now(), null,
                VALVE_BASE, Map.of());
        var sample = valveSample(NOW, 1.0, 0.6);
        var assessment = valve.score(point, sample, baseline, valve.diagnose(point, sample, baseline));
        assertEquals(30.0, assessment.healthIndex(), 1e-9, "泄漏击穿的静态分恰为 30.0");
        assertTrue(assessment.hardLimitBreached());
        when(alerts.save(any(AlertEntity.class))).thenAnswer(inv -> withId(inv.getArgument(0), 77L));

        var created = serviceWith(new DiagnosticMicrokernel(healthWindow)).observe(12L, sample, assessment, 1.0, 1.0, false);

        assertTrue(created.isPresent());
        assertEquals("DANGER", created.get().getAlertLevel(), "硬限击穿不得因 HI=30 落到 WARNING");
        assertEquals(0, created.get().getStatus());
        assertEquals(1, created.get().getOccurrenceCount());
        assertEquals("阀体内部严重空化冲刷或高压内漏 (超声声发射超标)", created.get().getDominantFactor());
        // 机理策略诊断的规则 ID/版本均为 null：合并查询照常进行，不因空值中断
        verify(alerts).findOpenOrSuppressed(isNull(), eq("DANGER"), any(), eq(21L), isNull(), isNull(), any());
        verify(workOrders).save(any(WorkOrderEntity.class));
        verify(notifications).enqueueAlert(77L);
        assertEquals(30.0, created.get().getDefectDetail().get("device_health_index"));
        assertEquals(30.0, created.get().getDefectDetail().get("point_health_index"));
    }

    /** 无硬限击穿时沿用 HI 分档：设备综合 HI 恰为 30 → WARNING（改造仅对硬限击穿生效）。 */
    @Test
    void withoutHardLimitBreachLevelStillFollowsDeviceIndex() {
        PointEntity point = valvePoint(30.0);
        var baseline = new BaselineSnapshot(12L, ModalityTypes.CONTROL_VALVE_PROCESS, "ACTIVE", 200, Instant.now(), null,
                VALVE_BASE, Map.of());
        var sample = valveSample(NOW, 3.0, 0.04);
        var assessment = valve.score(point, sample, baseline, valve.diagnose(point, sample, baseline));
        assertFalse(assessment.hardLimitBreached());
        var confirming = mock(DiagnosticMicrokernel.class);
        when(confirming.shouldTriggerAlarm(any(), any(), anyLong(), any(), anyBoolean())).thenReturn(true);
        when(alerts.save(any(AlertEntity.class))).thenAnswer(inv -> withId(inv.getArgument(0), 78L));

        var created = serviceWith(confirming).observe(12L, sample, assessment, 3.0, 1.0, false);

        assertEquals("WARNING", created.orElseThrow().getAlertLevel());
        verify(alerts).findOpenOrSuppressed(isNull(), eq("WARNING"), any(), eq(21L), isNull(), isNull(), any());
    }

    /** 已有同级别未闭环告警：只累加出现次数，不重复建单与入队。 */
    @Test
    void existingOpenAlertIsMergedWithoutNewWorkOrder() {
        PointEntity point = valvePoint(30.0);
        var baseline = valveBaseline();
        var sample = valveSample(NOW, 1.0, 0.6);
        var assessment = valve.score(point, sample, baseline, valve.diagnose(point, sample, baseline));
        AlertEntity existing = withId(new AlertEntity(point, "DANGER", 30.0, assessment.diagnosis(), 1.0, 1.0, List.of()), 9L);
        when(alerts.findOpenOrSuppressed(isNull(), eq("DANGER"), any(), eq(21L), isNull(), isNull(), any()))
                .thenReturn(List.of(existing));

        var merged = serviceWith(new DiagnosticMicrokernel(healthWindow)).observe(12L, sample, assessment, 1.0, 1.0, false);

        assertEquals(9L, merged.orElseThrow().getId());
        assertEquals(2, existing.getOccurrenceCount());
        verify(alerts, never()).save(any(AlertEntity.class));
        verify(workOrders, never()).save(any(WorkOrderEntity.class));
        verify(notifications, never()).enqueueAlert(any());
    }

    /** 同级别告警已被抑制（status=3）：不再重新建单。 */
    @Test
    void suppressedAlertOfSameLevelStaysSuppressed() {
        PointEntity point = valvePoint(30.0);
        var baseline = valveBaseline();
        var sample = valveSample(NOW, 1.0, 0.6);
        var assessment = valve.score(point, sample, baseline, valve.diagnose(point, sample, baseline));
        AlertEntity suppressed = withId(new AlertEntity(point, "DANGER", 30.0, assessment.diagnosis(), 1.0, 1.0, List.of()), 9L);
        suppressed.suppress();
        when(alerts.findOpenOrSuppressed(isNull(), eq("DANGER"), any(), eq(21L), isNull(), isNull(), any()))
                .thenReturn(List.of(suppressed));

        var result = serviceWith(new DiagnosticMicrokernel(healthWindow)).observe(12L, sample, assessment, 1.0, 1.0, false);

        assertTrue(result.isEmpty());
        verify(workOrders, never()).save(any(WorkOrderEntity.class));
    }

    /** 告警视图契约：携带测点模态类型与触发特征编码（JSON 键 modalityType / triggerFeatureCode）。 */
    @Test
    void alertViewCarriesModalityTypeAndTriggerFeatureCode() {
        PointEntity point = valvePoint(30.0);
        var sample = valveSample(NOW, 1.0, 0.6);
        AlertEntity alert = withId(new AlertEntity(point, "DANGER", 30.0, valve.diagnose(point, sample, valveBaseline()),
                1.0, 1.0, List.of()), 5L);
        when(alerts.findById(5L)).thenReturn(Optional.of(alert));
        when(notifications.deliveries(any())).thenReturn(List.of());

        var view = serviceWith(new DiagnosticMicrokernel(healthWindow)).get(5L);

        assertEquals("CONTROL_VALVE_PROCESS", view.modalityType());
        assertEquals("hysteresis_deadband_pct", view.triggerFeatureCode());
        String json = new JsonMapper().writeValueAsString(view);
        assertTrue(json.contains("\"modalityType\":\"CONTROL_VALVE_PROCESS\""), json);
        assertTrue(json.contains("\"triggerFeatureCode\":\"hysteresis_deadband_pct\""), json);
    }

    @Test
    void alertViewIsTolerantToUnregisteredModalityAndExposesVibrationTriggerFeature() {
        PointEntity unknown = valvePoint(30.0);
        unknown.setModalityType("ULTRASONIC_HIVE");
        PointEntity vibration = valvePoint(80.0);
        vibration.setModalityType(ModalityTypes.ROTATING_VIB);
        AlertEntity unknownAlert = withId(new AlertEntity(unknown, "DANGER", 30.0,
                valve.diagnose(unknown, valveSample(NOW, 1.0, 0.6), valveBaseline()), 1.0, 1.0, List.of()), 1L);
        AlertEntity vibrationAlert = withId(new AlertEntity(vibration, "WARNING", 80.0,
                valve.diagnose(vibration, valveSample(NOW, 1.0, 0.04), valveBaseline()), 1.0, 1.0, List.of()), 2L);
        when(alerts.findById(1L)).thenReturn(Optional.of(unknownAlert));
        when(alerts.findById(2L)).thenReturn(Optional.of(vibrationAlert));
        when(notifications.deliveries(any())).thenReturn(List.of());
        var service = serviceWith(new DiagnosticMicrokernel(healthWindow));

        assertEquals("ULTRASONIC_HIVE", service.get(1L).modalityType());
        assertNull(service.get(1L).triggerFeatureCode(), "模态未注册时触发特征编码为 null，不抛异常");
        assertEquals("ROTATING_VIB", service.get(2L).modalityType());
        assertEquals("freq_1x_amp", service.get(2L).triggerFeatureCode());
    }

    // ---------- 测试装配 ----------

    private AlertService serviceWith(DiagnosticMicrokernel kernel) {
        return new AlertService(healthWindow, alerts, workOrders, points, notifications, freshness, kernel, plugins);
    }

    private static BaselineSnapshot valveBaseline() {
        return new BaselineSnapshot(12L, ModalityTypes.CONTROL_VALVE_PROCESS, "ACTIVE", 200, Instant.now(), null,
                VALVE_BASE, Map.of());
    }

    /** 已生效且在线的调节阀测点：最近健康分由 lastIndex 给出（设备综合 HI 取已评分测点的最小值）。 */
    private PointEntity valvePoint(double lastIndex) {
        var type = mock(DeviceTypeEntity.class);
        when(type.getId()).thenReturn(21L);
        when(type.getTypeName()).thenReturn("调节阀");
        var device = new DeviceEntity(new FactoryEntity("FACT_01", "一号工厂"), "FCV101", "给水调节阀", null, null, type);
        PointEntity point = new PointEntity(device, "LOOP", "回环路", null);
        point.setModalityType(ModalityTypes.CONTROL_VALVE_PROCESS);
        point.setBaselineStatus(BaselineService.ACTIVE);
        point.setLastHealthIndex(lastIndex);
        point.setLastStatus("WARNING");
        point.recordIngest(Instant.now(), Instant.now(), 0);
        when(points.findById(any())).thenReturn(Optional.of(point));
        when(points.findByDevice_IdOrderByPointCode(any())).thenReturn(List.of(point));
        when(freshness.byModality()).thenReturn(modality -> Duration.ofMinutes(5));
        return point;
    }

    private static TelemetryEnvelope valveSample(long timestamp, double deadband, double leak) {
        return new TelemetryEnvelope(2, ModalityTypes.CONTROL_VALVE_PROCESS, timestamp, "FACT_01", "FCV101", "LOOP", 0,
                new OperatingCondition(null, null, true),
                Map.of("hysteresis_deadband_pct", deadband, "ultrasonic_leak_rms", leak), null, null);
    }

    private static AlertEntity withId(AlertEntity alert, Long id) {
        try {
            var field = AlertEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(alert, id);
            return alert;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static RotatingVibrationPlugin vibrationPlugin() {
        return new RotatingVibrationPlugin(new HealthIndexCalculator(), null,
                new DegradationTracker(Duration.ofHours(24)));
    }

    private static CircuitBreakerPlugin breakerPlugin() {
        return new CircuitBreakerPlugin(new SwitchgearProperties(true, 1.10, 1.20, 0.80, Duration.ofDays(30),
                Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(30)));
    }
}
