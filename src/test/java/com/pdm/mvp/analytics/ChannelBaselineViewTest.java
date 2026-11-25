package com.pdm.mvp.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.FactoryEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.modality.ModalityPluginRegistry;
import com.pdm.mvp.switchgear.CircuitBreakerPlugin;
import com.pdm.mvp.switchgear.SwitchgearProperties;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * v2 通道定位与基线视图：{code} 按 ID/编码双语义解析（重码 409、缺失 404），基线矩阵按契约字段名输出。
 */
class ChannelBaselineViewTest {

    @Test
    void numericSegmentResolvesAsPointId() {
        PointRepository repository = mock(PointRepository.class);
        PointEntity point = mock(PointEntity.class);
        when(repository.findById(7L)).thenReturn(Optional.of(point));
        AssetLookup lookup = new AssetLookup(repository);

        assertEquals(point, lookup.resolvePoint("7"));
    }

    @Test
    void codeSegmentResolvesGloballyByPointCode() {
        PointRepository repository = mock(PointRepository.class);
        PointEntity point = mock(PointEntity.class);
        when(repository.findByPointCodeOrderByIdAsc("CH_DE_RADIAL")).thenReturn(List.of(point));
        AssetLookup lookup = new AssetLookup(repository);

        assertEquals(point, lookup.resolvePoint("CH_DE_RADIAL"));
    }

    @Test
    void ambiguousPointCodeConflicts() {
        PointRepository repository = mock(PointRepository.class);
        when(repository.findByPointCodeOrderByIdAsc("DUP")).thenReturn(
                List.of(mock(PointEntity.class), mock(PointEntity.class)));
        AssetLookup lookup = new AssetLookup(repository);

        ResponseStatusException exception = org.junit.jupiter.api.Assertions
                .assertThrows(ResponseStatusException.class, () -> lookup.resolvePoint("DUP"));
        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    void unknownPointIsNotFound() {
        PointRepository repository = mock(PointRepository.class);
        when(repository.findByPointCodeOrderByIdAsc("NOPE")).thenReturn(List.of());
        AssetLookup lookup = new AssetLookup(repository);

        ResponseStatusException exception = org.junit.jupiter.api.Assertions
                .assertThrows(ResponseStatusException.class, () -> lookup.resolvePoint("NOPE"));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertEquals("测点不存在", exception.getReason());
    }

    @Test
    void channelBaselineAssemblesContractFieldsFromRealBaselineState() {
        PointEntity point = mock(PointEntity.class);
        when(point.getId()).thenReturn(7L);
        when(point.getPointCode()).thenReturn("CH_TRIP_UNIT");
        when(point.getModalityType()).thenReturn("SWITCHGEAR_EVENT");
        when(point.getBaselineStatus()).thenReturn((short) BaselineService.LEARNING);
        when(point.getLearnedSamples()).thenReturn(42);
        DeviceEntity device = mock(DeviceEntity.class);
        when(point.getDevice()).thenReturn(device);
        when(device.getId()).thenReturn(3L);
        when(device.getDeviceCode()).thenReturn("PUMP_P102A");
        FactoryEntity factory = mock(FactoryEntity.class);
        when(device.getFactory()).thenReturn(factory);
        when(factory.getFactoryCode()).thenReturn("FACT_01");

        PointRepository points = mock(PointRepository.class);
        when(points.findById(7L)).thenReturn(Optional.of(point));
        BaselineFeatureStateRepository featureStates = mock(BaselineFeatureStateRepository.class);
        BaselineFeatureStateEntity trip = new BaselineFeatureStateEntity(point, "SWITCHGEAR_EVENT", "trip_duration_ms");
        trip.setP50(40.1);
        trip.setCv(0.05);
        trip.setSampleCount(42);
        trip.setStatus(BaselineService.LEARNING);
        BaselineFeatureStateEntity wear = new BaselineFeatureStateEntity(point, "SWITCHGEAR_EVENT", "contact_wear_ratio");
        wear.setSampleCount(42);
        wear.setStatus(BaselineService.LEARNING);
        when(featureStates.findByPoint_IdAndModalityType(7L, "SWITCHGEAR_EVENT")).thenReturn(List.of(trip, wear));
        BaselineService baselines = new BaselineService(points, mock(com.pdm.mvp.asset.DeviceRepository.class),
                mock(BaselineSampleRepository.class), featureStates,
                mock(ModalityBaselineSampleRepository.class), mock(com.pdm.mvp.analytics.SpectrumBaselineService.class), Duration.ofMinutes(6));
        var registry = new ModalityPluginRegistry(List.of(new CircuitBreakerPlugin(new SwitchgearProperties(true,
                1.10, 1.20, 0.80, Duration.ofDays(30), Duration.ofDays(365), Duration.ofDays(30), Duration.ofDays(30)))));
        AssetLookup lookup = mock(AssetLookup.class);
        when(lookup.resolvePoint("CH_TRIP_UNIT")).thenReturn(point);
        ChannelBaselineController controller = new ChannelBaselineController(lookup, baselines, registry);

        var view = controller.baseline("CH_TRIP_UNIT");

        assertEquals("CH_TRIP_UNIT", view.channelCode());
        assertEquals(7L, view.pointId());
        assertEquals(3L, view.deviceId());
        assertEquals("PUMP_P102A", view.deviceCode());
        assertEquals("FACT_01", view.factoryCode());
        assertEquals("SWITCHGEAR_EVENT", view.modalityType());
        assertEquals("switchgearEventPlugin", view.pluginIdentifier());
        assertEquals("EVENT", view.cadence());
        assertEquals("LEARNING", view.status());
        assertEquals(42, view.learnedSamples());
        assertEquals(BaselineService.MINIMUM_LEARNING_SAMPLES, view.minimumSamples());
        assertNull(view.lockedAt());
        assertNull(view.warning());
        assertEquals(Duration.ofDays(365).toSeconds(), view.learningWindowSeconds(),
                "学习窗口应为插件声明的样本保留期");
        assertEquals(true, view.pooled());
        assertEquals(2, view.featureStates().size());
        var first = view.featureStates().get(0);
        assertEquals("trip_duration_ms", first.featureKey());
        assertEquals(40.1, first.baselineP50(), 1e-9);
        assertEquals(0.05, first.cvValue(), 1e-9);
        assertEquals(42, first.sampleCount());
        assertEquals("LEARNING", first.status());
        assertNull(first.lockedAt());
        assertNull(view.featureStates().get(1).baselineP50(), "缺失 P50/CV 应保持 null");
        assertNull(view.featureStates().get(1).cvValue());
    }
}
