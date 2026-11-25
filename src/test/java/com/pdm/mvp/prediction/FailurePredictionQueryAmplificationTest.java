package com.pdm.mvp.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pdm.mvp.analytics.BaselineService;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.DeviceRepository;
import com.pdm.mvp.asset.FactoryEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.modality.ModalityPluginRegistry;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.modality.TelemetryFreshnessPolicy;
import com.pdm.mvp.telemetry.TelemetryPoint;
import com.pdm.mvp.timeseries.TelemetryStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RUL 寿命预测查询放大消除与检修静默验证：
 * 常规场景 30 天回溯合并为单次 1h 聚合查询（原为串行 30 次）；仅命中 Parquet 文件上限时自动二分降级。
 */
class FailurePredictionQueryAmplificationTest {

    @Test
    void singleQueryFetchesThirtyDayWindowAndFallsBackToBisectOnlyOnFileLimit() {
        PredictionPointReader reader = mock(PredictionPointReader.class);
        TelemetryStore store = mock(TelemetryStore.class);
        TelemetryFreshnessPolicy freshness = new TelemetryFreshnessPolicy(new ModalityPluginRegistry(List.of()), Duration.ofMinutes(5));
        FailurePredictionService service = new FailurePredictionService(reader, store, freshness);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        var snapshot = new PredictionPointReader.PointSnapshot(1L, "FACT_01", "PUMP_01", "DE", "驱动端",
                BaselineService.ACTIVE, now.minus(Duration.ofDays(30)), 72.0, now.minusSeconds(30), now.minusSeconds(30),
                0, ModalityTypes.ROTATING_VIB);
        when(reader.load(10L)).thenReturn(List.of(snapshot));

        List<TelemetryPoint> hourly = new ArrayList<>();
        for (int h = 72; h >= 0; h--) {
            double hi = 72.0 + h * 0.25;
            hourly.add(new TelemetryPoint(now.minus(h, ChronoUnit.HOURS), "FACT_01", "PUMP_01", "DE",
                    ModalityTypes.ROTATING_VIB, 1.0, 2.0, 3.0, 0.5, 0.2, null, null, 45.0, 1500.0,
                    hi, 0, false, 0, "HEALTHY", null, Map.of(), null));
        }
        when(store.query(any(), any(), any(), eq("1h"))).thenReturn(hourly);

        FailurePrediction result = service.estimate(10L);
        assertEquals(FailurePrediction.Status.ESTIMATE_AVAILABLE, result.status());
        verify(store, times(1)).query(any(), any(), any(), eq("1h"));

        // 命中 InfluxDB 3 Core Parquet 文件上限时自动二分降级完成计算
        AtomicInteger calls = new AtomicInteger();
        when(store.query(any(), any(), any(), eq("1h"))).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new RuntimeException("Query would scan 500 Parquet files, exceeding the file limit");
            }
            return hourly;
        });
        FailurePrediction bisected = service.estimate(10L);
        assertEquals(FailurePrediction.Status.ESTIMATE_AVAILABLE, bisected.status());
        assertEquals(3, calls.get(), "首次整窗超限时二分拆成两半即可完成");
    }

    @Test
    void maintenanceDeviceSkipsInfluxQueriesCompletely() {
        DeviceRepository devices = mock(DeviceRepository.class);
        PointRepository points = mock(PointRepository.class);
        FactoryEntity factory = new FactoryEntity("FACT_01", "一车间");
        DeviceEntity device = new DeviceEntity(factory, "PUMP_01", "水泵", new BigDecimal("1500"), null, null);
        device.update("水泵", new BigDecimal("1500"), null, DeviceEntity.STATUS_MAINTENANCE, null, "CLASS_II");
        ReflectionTestUtils.setField(device, "id", 10L);
        when(devices.findById(10L)).thenReturn(Optional.of(device));

        PredictionPointReader reader = new PredictionPointReader(devices, points);
        assertTrue(reader.load(10L).isEmpty(), "检修态设备不应触发下游 30 天时序查询");
    }
}
