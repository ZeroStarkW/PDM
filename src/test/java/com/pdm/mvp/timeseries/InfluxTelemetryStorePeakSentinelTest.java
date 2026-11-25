package com.pdm.mvp.timeseries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.Point;
import com.influxdb.v3.client.PointValues;
import com.influxdb.v3.client.write.WritePrecision;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.FactoryEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.telemetry.TelemetryPoint;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 时序写入的 a_peak 哨兵与平台自有字段优先级：
 * 协议 2.1.0 旋转振动样本可缺省 a_peak（写 -1.0 保持列恒为 double，读回置 null）；
 * 特征码与平台字段同名时不得覆盖平台值或改变列类型。
 */
class InfluxTelemetryStorePeakSentinelTest {
    private static final Instant AT = Instant.ofEpochMilli(1732262400000L);

    /** 缺省 a_peak：a_peak 列仍以 double 写入哨兵 -1.0；带值时原样写入。 */
    @Test
    void missingPeakIsWrittenAsSentinelSoTheColumnStaysDouble() {
        try (var store = new InfluxTelemetryStore(mock(InfluxDBClient.class))) {
            Point withoutPeak = store.toPoint(vibration(null, Map.of()));
            Point withPeak = store.toPoint(vibration(2.5, Map.of()));

            assertEquals(-1.0, withoutPeak.getField("a_peak"));
            assertEquals(Double.class, withoutPeak.getFieldType("a_peak"), "a_peak 必须始终按 double 写入");
            assertEquals(2.5, withPeak.getField("a_peak"));
            assertEquals(Double.class, withPeak.getFieldType("a_peak"));
        }
    }

    /** 平台自有字段最后写入：特征里的同名键（status/modality/health_index/data_quality…）不可能覆盖其值或类型。 */
    @Test
    void platformOwnedFieldsAreWrittenAfterFeatureFieldsAndWin() {
        Map<String, Double> hostile = new LinkedHashMap<>();
        hostile.put("status", 5.0);
        hostile.put("modality", 1.0);
        hostile.put("health_index", 5.0);
        hostile.put("data_quality", 3.0);
        hostile.put("status_code", 3.0);
        hostile.put("raw_snapshot_ref", 1.0);
        hostile.put("trip_duration_ms", 42.5);
        try (var store = new InfluxTelemetryStore(mock(InfluxDBClient.class))) {
            Point point = store.toPoint(sample("SWITCHGEAR_EVENT", null, "HEALTHY", 0, 88.5, hostile, "s3://phm-vault/a.bin"));

            assertEquals("HEALTHY", point.getField("status"));
            assertEquals(String.class, point.getFieldType("status"));
            assertEquals("SWITCHGEAR_EVENT", point.getField("modality"));
            assertEquals(88.5, point.getField("health_index"));
            assertEquals(0L, point.getField("data_quality"), "data_quality 必须保持整数类型");
            assertEquals(Long.class, point.getFieldType("data_quality"));
            assertEquals(Long.class, point.getFieldType("status_code"));
            assertEquals("s3://phm-vault/a.bin", point.getField("raw_snapshot_ref"));
            assertEquals(42.5, point.getField("trip_duration_ms"), "普通特征照常写入");
        }
    }

    /** 振动查询（query / queryFeatures）：SQL 用 NULLIF 过滤 a_peak 哨兵，哨兵行读回 null 而不是 0.0。 */
    @Test
    void vibrationQueryNullsOutThePeakSentinel() {
        InfluxDBClient client = mock(InfluxDBClient.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(client.queryPoints(sql.capture())).thenAnswer(inv -> Stream.of(
                row(1732262300000L, -1.0, 1.0), row(1732262400000L, 2.5, 1.2)));
        PointEntity point = vibrationPoint();
        when(point.getLastTelemetryAt()).thenReturn(AT);

        try (var store = new InfluxTelemetryStore(client)) {
            List<TelemetryPoint> samples = store.queryFeatures(point, AT.minusSeconds(100), AT, "5m", List.of("feat_3x_amp"));

            assertTrue(sql.getValue().contains("MAX(NULLIF(a_peak, -1.0)) AS a_peak"), sql.getValue());
            assertEquals(2, samples.size());
            // 行按时间升序输出（存储内部先 DESC 再 reversed）
            assertEquals(2.5, samples.get(0).aPeak(), "正常峰值原样读回");
            assertEquals(1.2, samples.get(0).vRms());
            assertNull(samples.get(1).aPeak(), "哨兵 -1.0 读回为 null");
            assertEquals(1.0, samples.get(1).vRms());
        }
    }

    /** 通道查询（queryChannel 的振动路径）：聚合与原始行片段都带 a_peak 哨兵过滤，metrics 对哨兵行省略 a_peak。 */
    @Test
    void channelQueryTreatsPeakSentinelAsMissing() {
        assertTrue(InfluxTelemetryStore.featureSelects(List.of("a_peak", "trip_duration_ms"))
                .contains("AVG(NULLIF(a_peak, -1.0)) AS a_peak"));
        assertTrue(InfluxTelemetryStore.rawFeatureSelects(List.of("a_peak", "v_rms"))
                .contains("NULLIF(a_peak, -1.0) AS a_peak"));

        InfluxDBClient client = mock(InfluxDBClient.class);
        when(client.queryPoints(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> Stream.of(
                PointValues.measurement("point_telemetry").setTimestamp(1732262400000000000L, WritePrecision.NS)
                        .setField("health_index", -1.0).setField("data_quality", 0L).setField("status", "BASELINE_LEARNING")
                        .setField("a_peak", -1.0).setField("v_rms", 1.2)));
        try (var store = new InfluxTelemetryStore(client)) {
            List<TelemetryStore.ChannelSample> samples = store.queryChannel(vibrationPoint(), AT.minusSeconds(60), AT,
                    "raw", List.of("a_peak", "v_rms"));

            assertEquals(1, samples.size());
            assertFalse(samples.getFirst().metrics().containsKey("a_peak"), "哨兵行不得输出 a_peak");
            assertEquals(1.2, samples.getFirst().metrics().get("v_rms"));
        }
    }

    private static PointValues row(long epochMilli, double aPeak, double vRms) {
        return PointValues.measurement("point_telemetry").setTimestamp(epochMilli * 1_000_000L, WritePrecision.NS)
                .setField("a_peak", aPeak).setField("v_rms", vRms).setField("data_quality", 0L);
    }

    private static TelemetryPoint vibration(Double aPeak, Map<String, Double> extraFeatures) {
        Map<String, Double> features = new LinkedHashMap<>(extraFeatures);
        features.put("v_rms", 1.0);
        features.put("a_kurtosis", 3.0);
        features.put("freq_1x_amp", 0.5);
        features.put("freq_2x_amp", 0.2);
        return new TelemetryPoint(AT, "FACT_01", "PUMP_P102A", "DE_RADIAL", "ROTATING_VIB", 1.0, aPeak, 3.0, 0.5, 0.2,
                null, null, null, null, null, 0, false, 0, "ANALYSIS_PENDING", null, features, null);
    }

    private static TelemetryPoint sample(String modality, Double aPeak, String status, int statusCode, Double healthIndex,
            Map<String, Double> features, String rawSnapshotRef) {
        return new TelemetryPoint(AT, "FACT_01", "BKR_10KV_02", "TRIP_UNIT", modality, null, aPeak, null, null, null,
                null, null, null, null, healthIndex, 0, false, statusCode, status, null, features, rawSnapshotRef);
    }

    private static PointEntity vibrationPoint() {
        FactoryEntity factory = mock(FactoryEntity.class);
        when(factory.getFactoryCode()).thenReturn("FACT_01");
        DeviceEntity device = mock(DeviceEntity.class);
        when(device.getDeviceCode()).thenReturn("PUMP_P102A");
        when(device.getFactory()).thenReturn(factory);
        PointEntity point = mock(PointEntity.class);
        when(point.getId()).thenReturn(1L);
        when(point.getPointCode()).thenReturn("DE_RADIAL");
        when(point.getDevice()).thenReturn(device);
        return point;
    }
}
