package com.pdm.mvp.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pdm.mvp.analytics.AnalyticsEngine;
import com.pdm.mvp.analytics.HealthAssessment;
import com.pdm.mvp.alert.AlertService;
import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.modality.ModalityPlugin;
import com.pdm.mvp.modality.ModalityPluginRegistry;
import com.pdm.mvp.modality.ModalityPlugin.ConditionGate;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import com.pdm.mvp.telemetry.TelemetryService;
import com.pdm.mvp.timeseries.TelemetryStore;
import com.pdm.mvp.telemetry.TelemetryValidator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * TelemetryService → 推流枢纽附加钩子：非振动模态每个被接受的样本都推 metrics 帧
 * （已评分帧带 hi + 分档；学习期/门禁/缓冲帧 hi 与分档为 null、status 携带样本状态）；
 * 振动模态不推送（v1 专责），质量异常样本不推流，枢纽缺席/抛错不影响遥测主链路。
 */
class StreamPublishHookTest {
    private static final long TS = 1732262400000L;

    private final AssetLookup assets = mock(AssetLookup.class);
    private final TelemetryValidator validator = mock(TelemetryValidator.class);
    private final TelemetryStore store = mock(TelemetryStore.class);
    private final AnalyticsEngine analytics = mock(AnalyticsEngine.class);
    private final PointRepository points = mock(PointRepository.class);
    private final AlertService alerts = mock(AlertService.class);
    private final ModalityPluginRegistry plugins = mock(ModalityPluginRegistry.class);
    private final ModalityStreamHub hub = mock(ModalityStreamHub.class);
    private final ModalityPlugin plugin = mock(ModalityPlugin.class);

    private TelemetryService service;
    private PointEntity point;
    private TelemetryEnvelope envelope;

    @BeforeEach
    void setUp() {
        service = new TelemetryService(assets, validator, store, analytics, points, alerts, plugins);
        ReflectionTestUtils.setField(service, "streamHub", hub);
        point = mock(PointEntity.class);
        DeviceEntity device = mock(DeviceEntity.class);
        when(device.getDeviceCode()).thenReturn("MOTOR_01");
        when(point.getId()).thenReturn(7L);
        when(point.getDevice()).thenReturn(device);
        when(point.getPointCode()).thenReturn("CH_MCSA");
        when(point.getModalityType()).thenReturn(ModalityTypes.MCSA);
        when(point.getLastReceivedAt()).thenReturn(null);
        when(plugins.require(any())).thenReturn(plugin);
        when(plugin.modalityType()).thenReturn(ModalityTypes.MCSA);
        when(plugin.gate(any())).thenReturn(ConditionGate.accept());
        when(plugin.alertsEnabled()).thenReturn(false);
        when(points.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private AnalyticsEngine.AnalyticsOutput assessedOutput() {
        HealthAssessment assessment = new HealthAssessment(98.0, 97.0, 97.5, "HEALTHY", 0, null);
        return new AnalyticsEngine.AnalyticsOutput(envelope, assessment, "HEALTHY", 0, null);
    }

    private AnalyticsEngine.AnalyticsOutput learningOutput(String status) {
        return new AnalyticsEngine.AnalyticsOutput(envelope, null, status, 0, null);
    }

    @Test
    void publishesMetricsFrameAfterNonVibrationAssessment() {
        Map<String, Double> features = new LinkedHashMap<>();
        features.put("sideband_db", 54.0);
        envelope = new TelemetryEnvelope(2, ModalityTypes.MCSA, TS, "FACT_01", "MOTOR_01", "CH_MCSA",
                0, null, features, null, null);
        when(assets.requirePoint("FACT_01", "MOTOR_01", "CH_MCSA")).thenReturn(point);
        when(analytics.observe(point, envelope, plugin)).thenReturn(Optional.of(assessedOutput()));

        var result = service.accept(envelope, "REST");

        assertEquals("HEALTHY", result.status());
        verify(hub).publishMetrics(7L, features, 97.5, "HEALTHY", ModalityTypes.MCSA, "CH_MCSA", TS, null);
    }

    @Test
    void learningSamplePublishesFrameWithoutScoreButWithStatus() {
        Map<String, Double> features = new LinkedHashMap<>();
        features.put("sideband_db", 54.0);
        envelope = new TelemetryEnvelope(2, ModalityTypes.MCSA, TS, "FACT_01", "MOTOR_01", "CH_MCSA",
                0, null, features, null, null);
        when(assets.requirePoint("FACT_01", "MOTOR_01", "CH_MCSA")).thenReturn(point);
        when(analytics.observe(point, envelope, plugin)).thenReturn(Optional.of(learningOutput("BASELINE_LEARNING")));

        var result = service.accept(envelope, "REST");

        assertEquals("BASELINE_LEARNING", result.status());
        // 学习帧：health_index/alarm_level 为 null，status 携带样本实际状态（前端渲染 PROVISIONAL 空态）
        verify(hub).publishMetrics(eq(7L), eq(features), isNull(), isNull(),
                eq(ModalityTypes.MCSA), eq("CH_MCSA"), eq(TS), eq("BASELINE_LEARNING"));
    }

    @Test
    void bufferedSamplePublishesAnalysisBufferingFrame() {
        Map<String, Double> features = new LinkedHashMap<>();
        envelope = new TelemetryEnvelope(2, ModalityTypes.MCSA, TS, "FACT_01", "MOTOR_01", "CH_MCSA",
                0, null, features, null, null);
        when(assets.requirePoint("FACT_01", "MOTOR_01", "CH_MCSA")).thenReturn(point);
        when(analytics.observe(point, envelope, plugin)).thenReturn(Optional.empty());

        var result = service.accept(envelope, "REST");

        assertEquals("ANALYSIS_BUFFERING", result.status());
        verify(hub).publishMetrics(eq(7L), eq(features), isNull(), isNull(),
                eq(ModalityTypes.MCSA), eq("CH_MCSA"), eq(TS), eq("ANALYSIS_BUFFERING"));
    }

    @Test
    void conditionRejectedSamplePublishesGateStatusFrame() {
        Map<String, Double> features = new LinkedHashMap<>();
        envelope = new TelemetryEnvelope(2, ModalityTypes.MCSA, TS, "FACT_01", "MOTOR_01", "CH_MCSA",
                0, null, features, null, null);
        when(assets.requirePoint("FACT_01", "MOTOR_01", "CH_MCSA")).thenReturn(point);
        when(plugin.gate(any())).thenReturn(ConditionGate.notAssessable("负载波动"));

        var result = service.accept(envelope, "REST");

        assertEquals(ModalityTypes.CONDITION_REJECTED, result.status());
        verify(hub).publishMetrics(eq(7L), eq(features), isNull(), isNull(),
                eq(ModalityTypes.MCSA), eq("CH_MCSA"), eq(TS), eq(ModalityTypes.CONDITION_REJECTED));
    }

    @Test
    void badQualitySampleIsNotStreamed() {
        Map<String, Double> features = new LinkedHashMap<>();
        envelope = new TelemetryEnvelope(2, ModalityTypes.MCSA, TS, "FACT_01", "MOTOR_01", "CH_MCSA",
                2, null, features, null, null);
        when(assets.requirePoint("FACT_01", "MOTOR_01", "CH_MCSA")).thenReturn(point);

        var result = service.accept(envelope, "REST");

        assertEquals("SENSOR_CHECK", result.status());
        verify(hub, never()).publishMetrics(any(), any(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void vibrationModalityIsNotPublishedOnV2() {
        Map<String, Double> features = new LinkedHashMap<>();
        features.put("v_rms", 3.0);
        envelope = new TelemetryEnvelope(2, ModalityTypes.ROTATING_VIB, TS, "FACT_01", "MOTOR_01", "CH_MCSA",
                0, null, features, null, null);
        when(assets.requirePoint("FACT_01", "MOTOR_01", "CH_MCSA")).thenReturn(point);
        when(analytics.observe(point, envelope, plugin)).thenReturn(Optional.of(assessedOutput()));

        service.accept(envelope, "REST");

        verify(hub, never()).publishMetrics(any(), any(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void publishFailureDoesNotBreakTelemetryIngest() {
        Map<String, Double> features = new LinkedHashMap<>();
        envelope = new TelemetryEnvelope(2, ModalityTypes.MCSA, TS, "FACT_01", "MOTOR_01", "CH_MCSA",
                0, null, features, null, null);
        when(assets.requirePoint("FACT_01", "MOTOR_01", "CH_MCSA")).thenReturn(point);
        when(analytics.observe(point, envelope, plugin)).thenReturn(Optional.of(assessedOutput()));
        org.mockito.Mockito.doThrow(new RuntimeException("sse down")).when(hub)
                .publishMetrics(any(), any(), any(), any(), any(), any(), anyLong(), any());

        var result = service.accept(envelope, "REST");

        assertEquals("HEALTHY", result.status());
        // 推流抛错被钩子吞掉：遥测主链路完整走完（去除 ANALYSIS_PENDING 双写后，评估完成样本单次落库）
        verify(store, org.mockito.Mockito.times(1)).write(any());
        verify(points).save(point);
    }
}
