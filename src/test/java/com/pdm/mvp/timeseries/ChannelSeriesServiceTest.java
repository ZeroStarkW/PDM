package com.pdm.mvp.timeseries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pdm.mvp.analytics.DegradationTracker;
import com.pdm.mvp.analytics.HealthIndexCalculator;
import com.pdm.mvp.analytics.RotatingVibrationPlugin;
import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.modality.ModalityPlugin;
import com.pdm.mvp.modality.ModalityPluginRegistry;
import com.pdm.mvp.telemetry.VibrationContractAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * v2 通道时序查询：步长与时间窗校验、特征列按模态选择、行序与稀疏指标视图组装。
 */
class ChannelSeriesServiceTest {
    private final AssetLookup assets = mock(AssetLookup.class);
    private final TelemetryStore store = mock(TelemetryStore.class);

    @Test
    void unknownStepIsRejected() {
        PointEntity point = point("SWITCHGEAR_EVENT");
        when(assets.resolvePoint("7")).thenReturn(point);
        ChannelSeriesService service = new ChannelSeriesService(assets, registry(), store);

        ResponseStatusException exception = org.junit.jupiter.api.Assertions
                .assertThrows(ResponseStatusException.class, () -> service.series("7", null, null, "2h"));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rawStepIsAccepted() {
        PointEntity point = point("ROTATING_VIB");
        when(assets.resolvePoint("7")).thenReturn(point);
        when(store.queryChannel(org.mockito.ArgumentMatchers.eq(point), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("raw"),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        ChannelSeriesService service = new ChannelSeriesService(assets, registry(), store);

        var view = service.series("7", null, null, "raw");
        assertEquals("raw", view.step());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> codes = ArgumentCaptor.forClass(List.class);
        verify(store).queryChannel(org.mockito.ArgumentMatchers.eq(point), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("raw"), codes.capture());
        assertEquals(VibrationContractAdapter.KNOWN_CODES, codes.getValue(),
                "振动通道应请求全部已知振动特征码");
    }

    @Test
    void nonVibrationChannelRequestsPluginSeriesCodes() {
        PointEntity point = point("SWITCHGEAR_EVENT");
        when(assets.resolvePoint("CH_TRIP_UNIT")).thenReturn(point);
        when(store.queryChannel(org.mockito.ArgumentMatchers.eq(point), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("5m"),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        ChannelSeriesService service = new ChannelSeriesService(assets, registry(), store);

        service.series("CH_TRIP_UNIT", null, null, "5m");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> codes = ArgumentCaptor.forClass(List.class);
        verify(store).queryChannel(org.mockito.ArgumentMatchers.eq(point), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("5m"), codes.capture());
        assertTrue(codes.getValue().containsAll(List.of("trip_duration_ms", "peak_coil_current_a")));
    }

    @Test
    void startMustBeBeforeEnd() {
        PointEntity point = point("SWITCHGEAR_EVENT");
        when(assets.resolvePoint("7")).thenReturn(point);
        ChannelSeriesService service = new ChannelSeriesService(assets, registry(), store);

        ResponseStatusException exception = org.junit.jupiter.api.Assertions
                .assertThrows(ResponseStatusException.class,
                        () -> service.series("7", 1732262400000L, 1732262400000L, "5m"));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rowsAreAscendingAndFeaturesAreSortedUnionOfMetricKeys() {
        PointEntity point = point("SWITCHGEAR_EVENT");
        when(assets.resolvePoint("7")).thenReturn(point);
        when(store.queryChannel(org.mockito.ArgumentMatchers.eq(point), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("5m"),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new TelemetryStore.ChannelSample(Instant.ofEpochMilli(2000), 90.0, 0, "HEALTHY",
                        Map.of("b_metric", 2.0)),
                new TelemetryStore.ChannelSample(Instant.ofEpochMilli(1000), null, 0,
                        "HEALTHY_OR_BASELINE_LEARNING", Map.of("a_metric", 1.0)),
                new TelemetryStore.ChannelSample(Instant.ofEpochMilli(3000), null, 2, "SENSOR_CHECK",
                        Map.of("a_metric", 1.5, "c_metric", 0.3))));
        ChannelSeriesService service = new ChannelSeriesService(assets, registry(), store);

        var view = service.series("7", null, null, "5m");
        assertEquals(List.of("a_metric", "b_metric", "c_metric"), view.features(), "features 应为指标键的有序并集");
        assertEquals(List.of(1000L, 2000L, 3000L),
                view.rows().stream().map(ChannelSeriesService.ChannelSeriesView.Row::timestamp).toList(),
                "行必须按时间升序");
        assertTrue(!view.rows().get(1).metrics().containsKey("a_metric"), "稀疏行不携带缺失指标");
        assertEquals(2.0, view.rows().get(1).metrics().get("b_metric"), 1e-9);
        assertEquals("HEALTHY", view.rows().get(1).status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rowSerializesFrontendContractKeysWithEpochMilliTimestamp() {
        PointEntity point = point("SWITCHGEAR_EVENT");
        when(assets.resolvePoint("7")).thenReturn(point);
        when(store.queryChannel(org.mockito.ArgumentMatchers.eq(point), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("5m"),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new TelemetryStore.ChannelSample(Instant.ofEpochMilli(1732262400000L), null, 0,
                        "HEALTHY_OR_BASELINE_LEARNING", Map.of("trip_duration_ms", 41.5))));
        ChannelSeriesService service = new ChannelSeriesService(assets, registry(), store);

        var view = service.series("7", null, null, "5m");
        String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(view);
        Map<String, Object> body = tools.jackson.databind.json.JsonMapper.builder().build()
                .readValue(json, java.util.Map.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
        Map<String, Object> row = rows.getFirst();

        assertTrue(row.get("timestamp") instanceof Number, "前端 mergeLiveRow 需要数值 timestamp");
        assertEquals(1732262400000L, ((Number) row.get("timestamp")).longValue(),
                "timestamp 必须序列化为毫秒 epoch 数字");
        assertTrue(row.containsKey("health_index"), "行键必须是 snake_case 的 health_index");
        assertTrue(row.containsKey("data_quality"), "行键必须是 snake_case 的 data_quality");
        assertEquals("HEALTHY_OR_BASELINE_LEARNING", row.get("status"));
        assertEquals(41.5, ((Number) ((Map<String, Object>) row.get("metrics")).get("trip_duration_ms")).doubleValue(),
                1e-9);
    }

    @Test
    void aggregatedStepMapsSentinelFilteringForVibrationColumnsOnly() {
        String selects = InfluxTelemetryStore.featureSelects(
                List.of("envelope_rms", "temperature", "rpm", "trip_duration_ms", "v_rms"));
        assertTrue(selects.contains("AVG(NULLIF(envelope_rms, -1.0)) AS envelope_rms"));
        assertTrue(selects.contains("AVG(NULLIF(temperature, -999.0)) AS temperature"));
        assertTrue(selects.contains("AVG(NULLIF(rpm, -1.0)) AS rpm"));
        assertTrue(selects.contains("AVG(trip_duration_ms) AS trip_duration_ms"),
                "非振动特征按原值聚合，无哨兵过滤");
        assertTrue(selects.contains("AVG(v_rms) AS v_rms"));
    }

    @Test
    void unreasonableEpochBoundsAreRejectedWith400() {
        // B9：Long.MIN/MAX 与越界毫秒值不得 500，统一 400
        PointEntity point = point("SWITCHGEAR_EVENT");
        when(assets.resolvePoint("7")).thenReturn(point);
        ChannelSeriesService service = new ChannelSeriesService(assets, registry(), store);
        assertEquals(HttpStatus.BAD_REQUEST, org.junit.jupiter.api.Assertions
                .assertThrows(ResponseStatusException.class,
                        () -> service.series("7", Long.MIN_VALUE, null, "5m")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, org.junit.jupiter.api.Assertions
                .assertThrows(ResponseStatusException.class,
                        () -> service.series("7", null, Long.MAX_VALUE, "5m")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, org.junit.jupiter.api.Assertions
                .assertThrows(ResponseStatusException.class,
                        () -> service.series("7", 0L, null, "5m")).getStatusCode(),
                "1971-01-01 之前的毫秒值拒绝");
        assertEquals(HttpStatus.BAD_REQUEST, org.junit.jupiter.api.Assertions
                .assertThrows(ResponseStatusException.class,
                        () -> service.series("7", null, System.currentTimeMillis() + 2L * 24 * 3600 * 1000,
                                "5m")).getStatusCode(),
                "超过服务器时间 + 1 天的毫秒值拒绝");
    }

    @Test
    void truncatedFlagReportsRowLimitSaturation() {
        // B9：聚合路径行数触及 5000 上限 → truncated = true（行升序保留最新一端）
        PointEntity point = point("SWITCHGEAR_EVENT");
        when(assets.resolvePoint("7")).thenReturn(point);
        List<TelemetryStore.ChannelSample> saturated = new java.util.ArrayList<>();
        for (int i = 0; i < InfluxTelemetryStore.AGGREGATED_ROW_LIMIT; i++) {
            saturated.add(new TelemetryStore.ChannelSample(Instant.ofEpochMilli(i), null, 0, "HEALTHY", Map.of()));
        }
        when(store.queryChannel(org.mockito.ArgumentMatchers.eq(point), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("5m"),
                org.mockito.ArgumentMatchers.any())).thenReturn(saturated);
        ChannelSeriesService service = new ChannelSeriesService(assets, registry(), store);

        var view = service.series("7", null, null, "5m");
        assertTrue(view.truncated(), "行数 = 服务端上限时如实报告截断");
        assertEquals(InfluxTelemetryStore.AGGREGATED_ROW_LIMIT, view.rows().size());

        when(store.queryChannel(org.mockito.ArgumentMatchers.eq(point), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("5m"),
                org.mockito.ArgumentMatchers.any())).thenReturn(saturated.subList(0, 10));
        var partial = service.series("7", null, null, "5m");
        assertTrue(!partial.truncated(), "未触及上限不报截断");
    }

    private PointEntity point(String modalityType) {
        PointEntity point = mock(PointEntity.class);
        when(point.getId()).thenReturn(7L);
        when(point.getPointCode()).thenReturn("CH_TRIP_UNIT");
        when(point.getModalityType()).thenReturn(modalityType);
        return point;
    }

    private ModalityPluginRegistry registry() {
        ModalityPlugin eventPlugin = mock(ModalityPlugin.class);
        when(eventPlugin.modalityType()).thenReturn("SWITCHGEAR_EVENT");
        when(eventPlugin.seriesFeatureCodes()).thenReturn(new LinkedHashSet<>(List.of(
                "trip_duration_ms", "peak_coil_current_a")));
        return new ModalityPluginRegistry(List.of(
                new RotatingVibrationPlugin(new HealthIndexCalculator(), null,
                        new DegradationTracker(Duration.ofHours(24))),
                eventPlugin));
    }
}
