package com.pdm.mvp.timeseries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influxdb.v3.client.InfluxDBApiException;
import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.PointValues;
import com.influxdb.v3.client.write.WritePrecision;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.FactoryEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.telemetry.TelemetryPoint;
import com.pdm.mvp.telemetry.VibrationContractAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * InfluxDB 列缺失退化：可选特征从未上报时表里还没有该列，查询必须只剔除缺失列后重试，
 * 其余特征照常返回（否则通道时序与特征历史接口整体 500）。
 */
class InfluxTelemetryStoreTest {
    /** InfluxDB 3 Core 对振动测点（仅写入 9 个既有列）请求扩展特征时的真实报错。 */
    private static final String NO_FIELD_NAMED_FEAT_3X = "Schema error: No field named feat_3x_amp. Valid fields are "
            + "point_telemetry.a_kurtosis, point_telemetry.a_peak, point_telemetry.data_quality, point_telemetry.device_code, "
            + "point_telemetry.envelope_bpfo_amp, point_telemetry.envelope_rms, point_telemetry.factory_id, "
            + "point_telemetry.freq_1x_amp, point_telemetry.freq_2x_amp, point_telemetry.health_index, point_telemetry.modality, "
            + "point_telemetry.point_code, point_telemetry.rpm, point_telemetry.status, point_telemetry.status_code, "
            + "point_telemetry.temperature, point_telemetry.time, point_telemetry.v_rms.";
    private static final List<String> EXISTING_COLUMNS = List.of("v_rms", "a_peak", "a_kurtosis", "freq_1x_amp",
            "freq_2x_amp", "envelope_rms", "envelope_bpfo_amp", "rpm", "temperature");

    @Test
    void influxdbNoFieldErrorDropsOnlyMissingColumns() {
        List<String> remaining = InfluxTelemetryStore.withoutMissingColumns(VibrationContractAdapter.KNOWN_CODES,
                new RuntimeException("query failed", new InfluxDBApiException(NO_FIELD_NAMED_FEAT_3X)));

        assertEquals(EXISTING_COLUMNS, remaining, "应按 Valid fields 一次剔除全部未建出的扩展列");
    }

    @Test
    void datafusionColumnNotFoundDropsOnlyTheNamedColumn() {
        List<String> remaining = InfluxTelemetryStore.withoutMissingColumns(VibrationContractAdapter.KNOWN_CODES,
                new InfluxDBApiException("Builder error: column \"env_bpfi_amp\" not found in schema"));

        assertEquals(VibrationContractAdapter.KNOWN_CODES.size() - 1, remaining.size());
        assertFalse(remaining.contains("env_bpfi_amp"));
        assertTrue(remaining.contains("feat_3x_amp"), "未点名的列应保留，交由后续重试判定");
    }

    @Test
    void unnamedColumnNotFoundFallsBackToBaseColumns() {
        assertEquals(List.of(), InfluxTelemetryStore.withoutMissingColumns(List.of("trip_duration_ms"),
                new InfluxDBApiException("Column does not exist")));
    }

    @Test
    void unrelatedErrorsAreNotRetried() {
        List<String> codes = List.of("v_rms", "a_peak");

        assertNull(InfluxTelemetryStore.withoutMissingColumns(codes,
                new InfluxDBApiException("Connection refused")), "非列缺失错误必须原样抛出");
        assertNull(InfluxTelemetryStore.withoutMissingColumns(codes,
                new InfluxDBApiException("No field named other_col")), "缺失列不在请求内时无列可剔除");
        assertNull(InfluxTelemetryStore.withoutMissingColumns(List.of(),
                new InfluxDBApiException(NO_FIELD_NAMED_FEAT_3X)), "无特征列的查询不存在列缺失重试");
    }

    @Test
    void queryChannelRetriesAggregationWithoutMissingColumns() {
        InfluxDBClient client = mock(InfluxDBClient.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(client.queryPoints(sql.capture()))
                .thenThrow(new InfluxDBApiException(NO_FIELD_NAMED_FEAT_3X))
                .thenReturn(Stream.of(PointValues.measurement("point_telemetry")
                        .setTimestamp(1732262400000000000L, WritePrecision.NS)
                        .setField("health_index", 88.5).setField("data_quality", 0L)
                        .setField("health_status", 0L).setField("stopped", 0L)
                        .setField("v_rms", 1.2).setField("a_peak", 2.0)));

        try (InfluxTelemetryStore store = new InfluxTelemetryStore(client)) {
            List<TelemetryStore.ChannelSample> samples = store.queryChannel(vibrationPoint(),
                    Instant.ofEpochMilli(1732262300000L), Instant.ofEpochMilli(1732262400000L), "5m",
                    VibrationContractAdapter.KNOWN_CODES);

            List<String> statements = sql.getAllValues();
            assertEquals(2, statements.size(), "列缺失应只重试一次");
            assertTrue(statements.get(0).contains("feat_3x_amp"));
            assertFalse(statements.get(1).contains("feat_3x_amp"));
            assertFalse(statements.get(1).contains("temp_rise"));
            assertTrue(statements.get(1).contains("AVG(v_rms) AS v_rms"), "已建出的特征列必须照常聚合");
            assertEquals(1, samples.size());
            assertEquals(Instant.ofEpochMilli(1732262400000L), samples.getFirst().timestamp());
            assertEquals(88.5, samples.getFirst().healthIndex());
            assertEquals(List.of("v_rms", "a_peak"), List.copyOf(samples.getFirst().metrics().keySet()));
        }
    }

    @Test
    void queryFeaturesFallsBackToBaseColumnsWhenExtendedColumnIsMissing() {
        InfluxDBClient client = mock(InfluxDBClient.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(client.queryPoints(sql.capture()))
                .thenThrow(new InfluxDBApiException(NO_FIELD_NAMED_FEAT_3X))
                .thenReturn(Stream.of(PointValues.measurement("point_telemetry")
                        .setTimestamp(1732262400000000000L, WritePrecision.NS)
                        .setField("v_rms", 1.2).setField("health_index", 88.5).setField("data_quality", 0L)));
        PointEntity point = vibrationPoint();
        when(point.getLastTelemetryAt()).thenReturn(Instant.parse("2026-11-23T16:20:00Z"));

        try (InfluxTelemetryStore store = new InfluxTelemetryStore(client)) {
            List<TelemetryPoint> samples = store.queryFeatures(point,
                    Instant.ofEpochMilli(1732262300000L), Instant.ofEpochMilli(1732262400000L), "5m",
                    List.of("feat_3x_amp"));

            List<String> statements = sql.getAllValues();
            assertEquals(2, statements.size(), "扩展特征列缺失时应退化为基础列查询");
            assertTrue(statements.get(1).contains("AVG(v_rms) AS v_rms"));
            assertFalse(statements.get(1).contains("feat_3x_amp"));
            assertEquals(1, samples.size());
        }
    }

    @Test
    void queryChannelRetriesRawRowsWithoutAggregatesOrMissingColumns() {
        InfluxDBClient client = mock(InfluxDBClient.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(client.queryPoints(sql.capture()))
                .thenThrow(new InfluxDBApiException(NO_FIELD_NAMED_FEAT_3X))
                .thenReturn(Stream.of(PointValues.measurement("point_telemetry")
                        .setTimestamp(1732262400000000000L, WritePrecision.NS)
                        .setField("health_index", 88.5).setField("data_quality", 0L)
                        .setField("status", "HEALTHY").setField("v_rms", 1.2).setField("temperature", -999.0)));

        try (InfluxTelemetryStore store = new InfluxTelemetryStore(client)) {
            List<TelemetryStore.ChannelSample> samples = store.queryChannel(vibrationPoint(),
                    Instant.ofEpochMilli(1732262300000L), Instant.ofEpochMilli(1732262400000L), "raw",
                    VibrationContractAdapter.KNOWN_CODES);

            List<String> statements = sql.getAllValues();
            assertEquals(2, statements.size(), "列缺失应只重试一次");
            String retry = statements.get(1);
            assertFalse(retry.contains("AVG("), "原始行查询不能混入聚合函数：没有 GROUP BY 会被 InfluxDB 拒绝");
            assertTrue(retry.contains("v_rms AS v_rms"));
            assertTrue(retry.contains("NULLIF(temperature, -999.0) AS temperature"), "振动既有列的哨兵值仍需置空");
            assertFalse(retry.contains("temp_rise"));
            assertEquals(1, samples.size());
            assertEquals("HEALTHY", samples.getFirst().status());
            assertEquals(1.2, samples.getFirst().metrics().get("v_rms"));
            assertFalse(samples.getFirst().metrics().containsKey("temperature"));
        }
    }

    @Test
    void queryChannelRethrowsErrorsUnrelatedToSchema() {
        InfluxDBClient client = mock(InfluxDBClient.class);
        when(client.queryPoints(anyString())).thenThrow(new InfluxDBApiException("Connection refused"));

        try (InfluxTelemetryStore store = new InfluxTelemetryStore(client)) {
            InfluxDBApiException exception = assertThrows(InfluxDBApiException.class, () -> store.queryChannel(
                    vibrationPoint(), Instant.EPOCH, Instant.now(), "raw", VibrationContractAdapter.KNOWN_CODES));

            assertEquals("Connection refused", exception.getMessage());
            verify(client, times(1)).queryPoints(anyString());
        }
    }

    @Test
    void queryFileLimitSurfacesAsReadableErrorInsteadOfGeneric500() {
        InfluxDBClient client = mock(InfluxDBClient.class);
        when(client.queryPoints(anyString())).thenThrow(new RuntimeException("Error while logically planning query: "
                + "External error: Query would scan 432 Parquet files, exceeding the file limit. "
                + "InfluxDB 3 Core caps file access to prevent performance degradation and memory issues."));

        try (InfluxTelemetryStore store = new InfluxTelemetryStore(client)) {
            ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> store.queryChannel(
                    vibrationPoint(), Instant.now().minus(Duration.ofDays(7)), Instant.now(), "1h", List.of("v_rms")));

            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.getStatusCode());
            assertTrue(exception.getReason().contains("缩小时间范围"));
        }
    }

    /** 从未写入振动数据的部署：表里只有平台字段与新模态特征列，请求基础振动列时逐列降级。 */
    private static final String NO_VIBRATION_COLUMNS = "Schema error: No field named v_rms. Valid fields are "
            + "point_telemetry.data_quality, point_telemetry.device_code, point_telemetry.factory_id, "
            + "point_telemetry.health_index, point_telemetry.modality, point_telemetry.point_code, "
            + "point_telemetry.status, point_telemetry.status_code, point_telemetry.time, point_telemetry.cv_pct.";

    @Test
    void queryOnVibrationFreeDeploymentDropsMissingBaseColumns() {
        // B3：基础振动列缺失（v_rms 等 9 列不存在）→ 按现存列清单一次剔除全部缺失基础列后重试，而不是 500
        InfluxDBClient client = mock(InfluxDBClient.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(client.queryPoints(sql.capture()))
                .thenThrow(new InfluxDBApiException(NO_VIBRATION_COLUMNS))
                .thenReturn(Stream.of(PointValues.measurement("point_telemetry")
                        .setTimestamp(1732262400000000000L, WritePrecision.NS)
                        .setField("health_index", 88.5).setField("data_quality", 0L)
                        .setField("cv_pct", 12.0)));
        PointEntity point = vibrationPoint();
        when(point.getLastTelemetryAt()).thenReturn(Instant.parse("2026-11-23T16:20:00Z"));

        try (InfluxTelemetryStore store = new InfluxTelemetryStore(client)) {
            List<TelemetryPoint> samples = store.query(point,
                    Instant.ofEpochMilli(1732262300000L), Instant.ofEpochMilli(1732262400000L), "5m");

            List<String> statements = sql.getAllValues();
            assertEquals(2, statements.size(), "列缺失应只重试一次");
            assertTrue(statements.get(0).contains("AVG(v_rms) AS v_rms"));
            String retry = statements.get(1);
            assertFalse(retry.contains("AVG(v_rms)"), "缺失的基础列必须整段剔除");
            assertFalse(retry.contains("a_peak"));
            assertFalse(retry.contains("temperature"));
            assertTrue(retry.contains("AVG(NULLIF(health_index, -1.0)) AS health_index"), "平台字段照常聚合");
            assertEquals(1, samples.size());
            // 行形状保持：缺失列按既有哨兵机制映射（v_rms → 0.0，a_peak → null）
            assertEquals(0.0, samples.getFirst().vRms(), 1e-9);
            assertNull(samples.getFirst().aPeak());
            assertNull(samples.getFirst().temperature());
            assertEquals(88.5, samples.getFirst().healthIndex(), 1e-9);
        }
    }

    @Test
    void queryFeaturesDropsOnlyTheNamedExtraColumnPerRetry() {
        // B3：多个扩展特征缺失时只剔除点名的那一列逐次重试，不再整体退化到零扩展特征
        InfluxDBClient client = mock(InfluxDBClient.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(client.queryPoints(sql.capture()))
                .thenThrow(new InfluxDBApiException("Builder error: column \"temp_rise\" not found in schema"))
                .thenThrow(new InfluxDBApiException("Schema error: No field named feat_3x_amp. Valid fields are "
                        + "point_telemetry.v_rms, point_telemetry.a_peak, point_telemetry.a_kurtosis, "
                        + "point_telemetry.freq_1x_amp, point_telemetry.freq_2x_amp, point_telemetry.envelope_rms, "
                        + "point_telemetry.envelope_bpfo_amp, point_telemetry.rpm, point_telemetry.temperature, "
                        + "point_telemetry.feat_sub_amp, point_telemetry.time."))
                .thenReturn(Stream.of(PointValues.measurement("point_telemetry")
                        .setTimestamp(1732262400000000000L, WritePrecision.NS)
                        .setField("v_rms", 1.2).setField("health_index", 88.5).setField("data_quality", 0L)
                        .setField("feat_sub_amp", 0.3)));
        PointEntity point = vibrationPoint();
        when(point.getLastTelemetryAt()).thenReturn(Instant.parse("2026-11-23T16:20:00Z"));

        try (InfluxTelemetryStore store = new InfluxTelemetryStore(client)) {
            List<TelemetryPoint> samples = store.queryFeatures(point,
                    Instant.ofEpochMilli(1732262300000L), Instant.ofEpochMilli(1732262400000L), "5m",
                    List.of("feat_3x_amp", "temp_rise", "feat_sub_amp"));

            List<String> statements = sql.getAllValues();
            assertEquals(3, statements.size(), "每次重试只剔除缺失列");
            assertTrue(statements.get(0).contains("temp_rise") && statements.get(0).contains("feat_3x_amp"));
            assertTrue(statements.get(1).contains("feat_3x_amp"), "未点名的特征列保留");
            assertFalse(statements.get(1).contains("temp_rise"), "点名缺失的列先剔除");
            assertFalse(statements.get(2).contains("feat_3x_amp"));
            assertTrue(statements.get(2).contains("feat_sub_amp"), "幸存的扩展特征照常查询");
            assertEquals(1, samples.size());
            assertEquals(0.3, samples.getFirst().features().get("feat_sub_amp"), 1e-9,
                    "幸存的扩展特征照常返回");
        }
    }

    private PointEntity vibrationPoint() {
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
