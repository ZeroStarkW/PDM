package com.pdm.mvp.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pdm.mvp.alert.AlertEntity;
import com.pdm.mvp.alert.AlertRepository;
import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.snapshot.ChannelSnapshotViews.ChannelSnapshotView;
import com.pdm.mvp.waveform.WaveformSnapshotEntity;
import com.pdm.mvp.waveform.WaveformSnapshotRepository;
import com.pdm.mvp.waveform.WaveformStorage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 通道快照服务：断路器/调节阀装配、振动降级回退、协议校验、惰性标注、曲线/时间戳上限与告警关联。 */
class ChannelSnapshotServiceTest {
    /** REST 直推要求时间戳在服务器时间 ±1 天内：入库路径用例统一使用当前时间戳。 */
    private static final long TS = Instant.now().toEpochMilli();
    private static final long TSL = TS;
    private static final String BREAKER_PAYLOAD = """
            {"protocol_version":"2.1.0","timestamp":%d,"asset_id":"BREAKER_10KV_02",
             "channel_id":"CH_TRIP_UNIT","modality_type":"SWITCHGEAR_EVENT","trigger_type":"TRIP_ACTION_BURST",
             "sampling_rate":10000,"points_count":1000,"storage_backend":"MINIO_DIRECT",
             "snapshot_url":"s3://phm-vault/20261122/BREAKER_10KV_02/1732262400000.bin",
             "preview_curve":[0.0,0.1,1.2,3.5,5.42,4.8,1.2,0.1,0.0],
             "curves":{"time_axis_ms":[0.0,5.0,10.0,15.0,20.0,25.0,30.0,35.0,40.0,45.0],
                       "coil_current_a":[0.0,0.8,3.2,5.42,4.1,2.0,0.5,0.1,0.0,0.0],
                       "travel_stroke_mm":[0.0,0.0,0.2,1.5,4.8,9.2,14.5,18.2,20.0,20.0]},
             "curve_units":{"coil_current_a":"A"},
             "metrics":{"trip_duration_ms":42.5},"health_index":96.5,"data_quality":0}
            """.formatted(TS);

    private final ChannelSnapshotRepository snapshots = mock(ChannelSnapshotRepository.class);
    private final WaveformSnapshotRepository vibrationSnapshots = mock(WaveformSnapshotRepository.class);
    private final WaveformStorage storage = mock(WaveformStorage.class);
    private final AssetLookup assets = mock(AssetLookup.class);
    private final AlertRepository alerts = mock(AlertRepository.class);
    private final ModalityStreamHub hub = mock(ModalityStreamHub.class);
    private final ChannelSnapshotService service = new ChannelSnapshotService(snapshots, vibrationSnapshots,
            storage, assets, alerts, hub, new JsonMapper(), 16384);

    @Test
    void assemblesBreakerSnapshotWithStrokeAnnotations() {
        PointEntity point = point(ModalityTypes.SWITCHGEAR_EVENT, "CH_TRIP_UNIT");
        when(assets.requirePointByDeviceAndPoint("BREAKER_10KV_02", "CH_TRIP_UNIT")).thenReturn(point);
        when(storage.store(eq(7L), eq(TSL), any())).thenReturn("file:/data/waveforms/7/1732262400000.json");
        when(snapshots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PhmSnapshotPayload payload = new JsonMapper().readValue(BREAKER_PAYLOAD, PhmSnapshotPayload.class);
        ChannelSnapshotView view = service.accept(payload);

        assertEquals("CH_TRIP_UNIT", view.channelCode());
        assertEquals("BREAKER_10KV_02", view.deviceCode());
        assertEquals(ModalityTypes.SWITCHGEAR_EVENT, view.modalityType());
        assertEquals("TRIP_ACTION_BURST", view.triggerType());
        assertEquals(TSL, view.timestamp());
        assertEquals(Instant.ofEpochMilli(TSL).toString(), view.sampledAt());
        assertEquals(10000.0, view.samplingRate(), 1e-9);
        assertEquals(1000, view.pointsCount());
        assertEquals("A", view.engineeringUnit());
        assertEquals("LOCAL_VOLUME", view.storageBackend(), "平台始终本地卷归档");
        assertEquals("file:/data/waveforms/7/1732262400000.json", view.storageRef());
        assertEquals("s3://phm-vault/20261122/BREAKER_10KV_02/1732262400000.bin", view.snapshotUrl());
        assertEquals(96.5, view.healthIndex(), 1e-9);
        assertEquals(9, view.previewCurve().size());
        assertEquals(42.5, view.metrics().get("trip_duration_ms"), 1e-9);
        assertTrue(view.annotations().containsKey("tripStartMs"), "标注键为前端契约 camelCase");
        assertTrue(view.annotations().containsKey("peakCurrentA"));
        assertTrue(view.annotations().containsKey("maxVelocityMps"));
        verify(hub).publishSnapshot(eq(7L), eq(view));
    }

    @Test
    void assemblesValveSnapshotWithLoopAnnotations() {
        PointEntity point = point(ModalityTypes.CONTROL_VALVE_PROCESS, "CH_VALVE_LOOP");
        when(assets.requirePointByDeviceAndPoint("VALVE_01", "CH_VALVE_LOOP")).thenReturn(point);
        when(storage.store(anyLong(), anyLong(), any())).thenReturn("file:/data/waveforms/7/1000.json");
        when(snapshots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PhmSnapshotPayload payload = new PhmSnapshotPayload("2.1.0", TSL, "VALVE_01", "CH_VALVE_LOOP",
                null, "SCHEDULED", null, null, null, null, List.of(0.0, 10.0),
                Map.of("cv_pct", List.of(0.0, 25.0, 50.0, 25.0, 0.0),
                        "fb_pct", List.of(0.0, 23.0, 48.0, 25.0, 0.0)),
                Map.of("cv_pct", "%"), Map.of("loop_travel", 1.0), 88.0, 0);
        ChannelSnapshotView view = service.accept(payload);

        assertEquals(ModalityTypes.CONTROL_VALVE_PROCESS, view.modalityType());
        assertTrue(view.annotations().containsKey("deadbandEstimatePct"));
        assertTrue(view.annotations().containsKey("hysteresisPct"));
        assertEquals("%", view.curveUnits().get("cv_pct"));
        // 采样率缺省回落测点 sampling_rate（point mock 返回 null → 0）
        assertEquals(0.0, view.samplingRate(), 1e-9);
        verify(hub).publishSnapshot(eq(7L), eq(view));
    }

    @Test
    void fallsBackToLatestVibrationSnapshotForOtherModalities() {
        PointEntity point = point(ModalityTypes.ROTATING_VIB, "PUMP_DE_VIB");
        when(assets.resolvePoint("PUMP_DE_VIB")).thenReturn(point);
        WaveformSnapshotEntity waveform = mock(WaveformSnapshotEntity.class);
        when(waveform.getPoint()).thenReturn(point);
        when(waveform.getTriggerType()).thenReturn("THRESHOLD_EXCEEDED");
        when(waveform.getSampledAt()).thenReturn(Instant.ofEpochMilli(TSL));
        when(waveform.getSamplingRate()).thenReturn(10240.0);
        when(waveform.getPointsCount()).thenReturn(2);
        when(waveform.getUnit()).thenReturn("m/s^2");
        when(waveform.getStorageType()).thenReturn("LOCAL_VOLUME");
        when(waveform.getStorageRef()).thenReturn("file:/data/waveforms/7/1732262400000.json");
        when(waveform.getPreview()).thenReturn(List.of(0.1, 0.2));
        when(waveform.getHealthIndex()).thenReturn(96.5);
        when(vibrationSnapshots.findTopByPoint_IdOrderBySampledAtDesc(7L)).thenReturn(Optional.of(waveform));

        ChannelSnapshotView view = service.latest("PUMP_DE_VIB");
        assertEquals("m/s^2", view.engineeringUnit(), "振动回退取波形工程单位");
        assertEquals(List.of(0.1, 0.2), view.previewCurve());
        assertEquals(Map.of(), view.curves());
        assertEquals(Map.of(), view.annotations());
        assertEquals("THRESHOLD_EXCEEDED", view.triggerType());
        assertEquals("file:/data/waveforms/7/1732262400000.json", view.snapshotUrl());
        verify(hub, never()).publishSnapshot(anyLong(), any());
    }

    @Test
    void unknownChannelSnapshotYields404() {
        PointEntity point = point(ModalityTypes.SWITCHGEAR_EVENT, "CH_TRIP_UNIT");
        when(assets.resolvePoint("CH_TRIP_UNIT")).thenReturn(point);
        when(snapshots.findTopByPoint_IdOrderBySampledAtDesc(7L)).thenReturn(Optional.empty());
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.latest("CH_TRIP_UNIT"));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    @Test
    void vibrationFallbackWithoutSnapshotYields404() {
        PointEntity point = point(ModalityTypes.MCSA, "CH_MCSA");
        when(assets.resolvePoint("CH_MCSA")).thenReturn(point);
        when(vibrationSnapshots.findTopByPoint_IdOrderBySampledAtDesc(7L)).thenReturn(Optional.empty());
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.latest("CH_MCSA"));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    @Test
    void recomputesMissingAnnotationsLazily() {
        PointEntity point = point(ModalityTypes.SWITCHGEAR_EVENT, "CH_TRIP_UNIT");
        when(assets.resolvePoint("CH_TRIP_UNIT")).thenReturn(point);
        // 真实实体（setter 需要保留状态，供视图重建读取）
        ChannelSnapshotEntity stored = new ChannelSnapshotEntity(point, "TRIP_ACTION_BURST",
                Instant.ofEpochMilli(TSL), 10000.0, 10, List.of(0.0, 1.0), 96.5);
        stored.setCurves(Map.of(
                StrokeAnnotationCalculator.TIME_AXIS_MS, List.of(0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0, 40.0, 45.0),
                StrokeAnnotationCalculator.COIL_CURRENT_A, List.of(0.0, 0.8, 3.2, 5.42, 4.1, 2.0, 0.5, 0.1, 0.0, 0.0),
                StrokeAnnotationCalculator.TRAVEL_STROKE_MM, List.of(0.0, 0.0, 0.2, 1.5, 4.8, 9.2, 14.5, 18.2, 20.0, 20.0)));
        when(snapshots.findTopByPoint_IdOrderBySampledAtDesc(7L)).thenReturn(Optional.of(stored));
        when(snapshots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ChannelSnapshotView view = service.latest("CH_TRIP_UNIT");
        assertTrue(view.annotations().containsKey("tripStartMs"), "曲线在而标注为 null → 惰性补算");
        verify(snapshots).save(any());
    }

    @Test
    void annotationsStoredEmptyMapIsNotRecomputedForever() {
        PointEntity point = point(ModalityTypes.SWITCHGEAR_EVENT, "CH_TRIP_UNIT");
        when(assets.resolvePoint("CH_TRIP_UNIT")).thenReturn(point);
        ChannelSnapshotEntity stored = new ChannelSnapshotEntity(point, "SCHEDULED",
                Instant.ofEpochMilli(TSL), 10000.0, 2, List.of(0.0, 1.0), 96.5);
        stored.setCurves(Map.of("coil_current_a", List.of(0.0, 1.0)));
        // 已显式存过空标注 {} 的行不再每次 GET 重算（区别于 null：null 才补算）
        stored.setAnnotations(Map.of());
        when(snapshots.findTopByPoint_IdOrderBySampledAtDesc(7L)).thenReturn(Optional.of(stored));

        ChannelSnapshotView view = service.latest("CH_TRIP_UNIT");
        assertTrue(view.annotations().isEmpty());
        verify(snapshots, never()).save(any());
    }

    @Test
    void rejectsModalityMismatchWith400() {
        PointEntity point = point(ModalityTypes.SWITCHGEAR_EVENT, "CH_TRIP_UNIT");
        when(assets.requirePointByDeviceAndPoint("BREAKER_10KV_02", "CH_TRIP_UNIT")).thenReturn(point);
        PhmSnapshotPayload payload = new PhmSnapshotPayload("2.1.0", TSL, "BREAKER_10KV_02",
                "CH_TRIP_UNIT", ModalityTypes.ROTATING_VIB, "ON_DEMAND", null, null, null, null,
                List.of(0.0, 1.0), null, null, null, null, null);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.accept(payload));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("测点模态与快照模态不匹配", exception.getReason());
        verify(hub, never()).publishSnapshot(anyLong(), any());
    }

    @Test
    void vibrationAndMcsaPointsRejectChannelSnapshotPathWith400() {
        // B8(d)：振动/MCSA 快照必须走波形通道（波形降级读取路径保持不变）
        PointEntity point = point(ModalityTypes.ROTATING_VIB, "PUMP_DE_VIB");
        when(assets.requirePointByDeviceAndPoint("PUMP_P102A", "PUMP_DE_VIB")).thenReturn(point);
        PhmSnapshotPayload payload = new PhmSnapshotPayload("2.1.0", TSL, "PUMP_P102A",
                "PUMP_DE_VIB", null, "ON_DEMAND", null, null, null, null, List.of(0.0, 1.0), null, null, null, null, null);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.accept(payload));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("该模态使用振动波形通道", exception.getReason());
        PointEntity mcsa = point(ModalityTypes.MCSA, "CH_MCSA");
        when(assets.requirePointByDeviceAndPoint("MOTOR_01", "CH_MCSA")).thenReturn(mcsa);
        PhmSnapshotPayload mcsaPayload = new PhmSnapshotPayload("2.1.0", TSL, "MOTOR_01",
                "CH_MCSA", null, "ON_DEMAND", null, null, null, null, List.of(0.0, 1.0), null, null, null, null, null);
        ResponseStatusException mcsaException = assertThrows(ResponseStatusException.class, () -> service.accept(mcsaPayload));
        assertEquals("该模态使用振动波形通道", mcsaException.getReason());
        verify(storage, never()).store(anyLong(), anyLong(), any());
    }

    @Test
    void rejectsUnsupportedProtocolAndTriggerAndOversizedPreview() {
        ResponseStatusException version = assertThrows(ResponseStatusException.class,
                () -> service.accept(new PhmSnapshotPayload("2.0.0", TSL, "A", "C", null,
                        "ON_DEMAND", null, null, null, null, List.of(0.0), null, null, null, null, null)));
        assertTrue(version.getReason().contains("不支持的协议版本"));
        ResponseStatusException trigger = assertThrows(ResponseStatusException.class,
                () -> service.accept(new PhmSnapshotPayload("2.1.0", TSL, "A", "C", null,
                        "MANUAL_TEST", null, null, null, null, List.of(0.0), null, null, null, null, null)));
        assertTrue(trigger.getReason().contains("trigger_type"));
        List<Double> oversized = new java.util.ArrayList<>();
        for (int i = 0; i < 65; i++) oversized.add((double) i);
        ChannelSnapshotService tiny = new ChannelSnapshotService(snapshots, vibrationSnapshots, storage,
                assets, alerts, hub, new JsonMapper(), 64);
        ResponseStatusException preview = assertThrows(ResponseStatusException.class,
                () -> tiny.accept(new PhmSnapshotPayload("2.1.0", TSL, "A", "C", null,
                        "ON_DEMAND", null, null, null, null, oversized, null, null, null, null, null)));
        assertTrue(preview.getReason().contains("上限"));
    }

    @Test
    void curvesAreCappedAtEightEntriesAndSixteenThousandPointsEach() {
        // B8(a)：超过 8 条曲线 → 400，消息点名 curves 字段
        Map<String, List<Double>> tooMany = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 9; i++) tooMany.put("curve_" + i, List.of(0.0, 1.0));
        ResponseStatusException count = assertThrows(ResponseStatusException.class,
                () -> service.accept(new PhmSnapshotPayload("2.1.0", TSL, "A", "C", null,
                        "ON_DEMAND", null, null, null, null, List.of(0.0), tooMany, null, null, null, null)));
        assertTrue(count.getReason().contains("curves"), "消息必须点名 curves 字段");
        // 单条超过 16384 点 → 400，消息点名 curves.<key>
        List<Double> tooLong = new java.util.ArrayList<>();
        for (int i = 0; i <= PhmSnapshotPayload.MAX_CURVE_POINTS; i++) tooLong.add((double) i);
        ResponseStatusException length = assertThrows(ResponseStatusException.class,
                () -> service.accept(new PhmSnapshotPayload("2.1.0", TSL, "A", "C", null,
                        "ON_DEMAND", null, null, null, null, List.of(0.0),
                        Map.of("coil_current_a", tooLong), null, null, null, null)));
        assertTrue(length.getReason().contains("curves.coil_current_a"));
        verifyNoInteractions(storage);
    }

    @Test
    void healthIndexMustBeFiniteWithinZeroToHundred() {
        // B8(b)：health_index 越界/非有限 → 400
        ResponseStatusException tooHigh = assertThrows(ResponseStatusException.class,
                () -> service.accept(new PhmSnapshotPayload("2.1.0", TSL, "A", "C", null,
                        "ON_DEMAND", null, null, null, null, List.of(0.0), null, null, null, 543.0, null)));
        assertTrue(tooHigh.getReason().contains("health_index"));
        ResponseStatusException notFinite = assertThrows(ResponseStatusException.class,
                () -> service.accept(new PhmSnapshotPayload("2.1.0", TSL, "A", "C", null,
                        "ON_DEMAND", null, null, null, null, List.of(0.0), null, null, null, Double.NaN, null)));
        assertTrue(notFinite.getReason().contains("health_index"));
    }

    @Test
    void restAcceptRejectsTimestampBeyondOneDay() {
        // B8(c)：REST 直推时间戳 ±1 天之外 → 400
        long stale = Instant.now().minus(2, ChronoUnit.DAYS).toEpochMilli();
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.accept(new PhmSnapshotPayload("2.1.0", stale, "A", "C", null,
                        "ON_DEMAND", null, null, null, null, List.of(0.0), null, null, null, null, null)));
        assertTrue(exception.getReason().contains("1 天"));
    }

    @Test
    void mqttIngestToleratesStoreAndForwardButRejectsPoisonTimestamps() {
        // B8(c) 选择：MQTT 容忍断网补传（>1 天），但拒绝偏差超过 1 年的毒消息
        PointEntity point = point(ModalityTypes.SWITCHGEAR_EVENT, "CH_TRIP_UNIT");
        when(assets.requirePointByDeviceAndPoint("BREAKER_10KV_02", "CH_TRIP_UNIT")).thenReturn(point);
        when(storage.store(anyLong(), anyLong(), any())).thenReturn("file:/data/waveforms/7/x.json");
        when(snapshots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        PhmSnapshotPayload stale = new PhmSnapshotPayload("2.1.0",
                Instant.now().minus(100, ChronoUnit.DAYS).toEpochMilli(), "BREAKER_10KV_02", "CH_TRIP_UNIT",
                null, "SCHEDULED", 10000.0, null, null, null, List.of(0.0, 1.0), null, null, null, 96.0, 0);
        service.ingest("phm/snapshot/BREAKER_10KV_02/CH_TRIP_UNIT", new JsonMapper().writeValueAsString(stale));

        PhmSnapshotPayload poison = new PhmSnapshotPayload("2.1.0",
                Instant.now().minus(400, ChronoUnit.DAYS).toEpochMilli(), "BREAKER_10KV_02", "CH_TRIP_UNIT",
                null, "SCHEDULED", 10000.0, null, null, null, List.of(0.0, 1.0), null, null, null, 96.0, 0);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.ingest("phm/snapshot/BREAKER_10KV_02/CH_TRIP_UNIT",
                        new JsonMapper().writeValueAsString(poison)));
        assertTrue(exception.getReason().contains("1 年"));
    }

    @Test
    void duplicateTimestampSnapshotIsAnIdempotentReplay() {
        // B8(e)：同测点同采样时刻的快照重投（MQTT QoS1）→ 返回既有视图，不重写不重推
        PointEntity point = point(ModalityTypes.SWITCHGEAR_EVENT, "CH_TRIP_UNIT");
        when(assets.requirePointByDeviceAndPoint("BREAKER_10KV_02", "CH_TRIP_UNIT")).thenReturn(point);
        Instant sampledAt = Instant.ofEpochMilli(TSL);
        ChannelSnapshotEntity existing = new ChannelSnapshotEntity(point, "TRIP_ACTION_BURST",
                sampledAt, 10000.0, 2, List.of(0.0, 1.0), 96.5);
        when(snapshots.findByPoint_IdAndSampledAtOrderByIdDesc(7L, sampledAt)).thenReturn(Optional.of(existing));

        ChannelSnapshotView view = service.accept(new PhmSnapshotPayload("2.1.0", TSL,
                "BREAKER_10KV_02", "CH_TRIP_UNIT", null, "TRIP_ACTION_BURST", 10000.0, null, null, null,
                List.of(0.0, 1.0), null, null, null, 96.5, 0));

        assertEquals("CH_TRIP_UNIT", view.channelCode());
        verify(snapshots, never()).save(any());
        verify(storage, never()).store(anyLong(), anyLong(), any());
        verify(hub, never()).publishSnapshot(anyLong(), any());
    }

    @Test
    void concurrentUniqueViolationSurfacesAsConflictInsteadOf500() {
        PointEntity point = point(ModalityTypes.SWITCHGEAR_EVENT, "CH_TRIP_UNIT");
        when(assets.requirePointByDeviceAndPoint("BREAKER_10KV_02", "CH_TRIP_UNIT")).thenReturn(point);
        when(snapshots.findByPoint_IdAndSampledAtOrderByIdDesc(eq(7L), any())).thenReturn(Optional.empty());
        when(snapshots.save(any())).thenThrow(new DataIntegrityViolationException("uk_channel_snapshot_point_time"));
        when(storage.store(anyLong(), anyLong(), any())).thenReturn("file:/data/waveforms/7/x.json");
        PhmSnapshotPayload payload = new PhmSnapshotPayload("2.1.0", TSL,
                "BREAKER_10KV_02", "CH_TRIP_UNIT", null, "SCHEDULED", 10000.0, null, null, null,
                List.of(0.0, 1.0), null, null, null, null, 0);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.accept(payload));
        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(hub, never()).publishSnapshot(anyLong(), any());
    }

    @Test
    void tripSnapshotsLinkLatestOpenAlertStorageRef() {
        // B10：动作突发/越限快照回填最新未闭环告警（storage_ref），已有链接不覆盖
        PointEntity point = point(ModalityTypes.SWITCHGEAR_EVENT, "CH_TRIP_UNIT");
        when(assets.requirePointByDeviceAndPoint("BREAKER_10KV_02", "CH_TRIP_UNIT")).thenReturn(point);
        when(storage.store(anyLong(), anyLong(), any())).thenReturn("file:/data/waveforms/7/link.json");
        when(snapshots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AlertEntity alert = mock(AlertEntity.class);
        when(alert.getRawSnapshotUrl()).thenReturn(null);
        when(alerts.findTopByPoint_IdAndStatusLessThanOrderByCreatedAtDesc(7L, (short) 2)).thenReturn(alert);

        service.accept(new PhmSnapshotPayload("2.1.0", TSL, "BREAKER_10KV_02", "CH_TRIP_UNIT",
                null, "TRIP_ACTION_BURST", 10000.0, null, null, null, List.of(0.0, 1.0), null, null, null, null, 0));
        verify(alert).setRawSnapshotUrl("file:/data/waveforms/7/link.json");

        // SCHEDULED 快照不触发关联
        service.accept(new PhmSnapshotPayload("2.1.0", TSL + 1, "BREAKER_10KV_02", "CH_TRIP_UNIT",
                null, "SCHEDULED", 10000.0, null, null, null, List.of(0.0, 1.0), null, null, null, null, 0));
        verify(alert).setRawSnapshotUrl("file:/data/waveforms/7/link.json"); // 仅第一次

        // 已有链接不覆盖
        when(alert.getRawSnapshotUrl()).thenReturn("s3://existing");
        service.accept(new PhmSnapshotPayload("2.1.0", TSL + 2, "BREAKER_10KV_02", "CH_TRIP_UNIT",
                null, "THRESHOLD_EXCEEDED", 10000.0, null, null, null, List.of(0.0, 1.0), null, null, null, null, 0));
        verify(alert).setRawSnapshotUrl("file:/data/waveforms/7/link.json"); // 仍只一次
    }

    @Test
    void missingRequiredFieldsRejectWithoutStorage() {
        // 全空载荷在必填校验处先行拒绝，不触及存储
        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> service.accept(new PhmSnapshotPayload("2.1.0", null, "A", "C", null,
                        "ON_DEMAND", null, null, null, null, null, null, null, null, null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        verify(storage, never()).store(anyLong(), anyLong(), any());
    }

    @Test
    void nullAnnotationsAreSerializedAsEmptyMap() {
        // B12：契约键永不为 JSON null —— 标注缺失输出 {}
        PointEntity point = point(ModalityTypes.SWITCHGEAR_EVENT, "CH_TRIP_UNIT");
        ChannelSnapshotEntity stored = new ChannelSnapshotEntity(point, "SCHEDULED",
                Instant.ofEpochMilli(TSL), 10000.0, 2, List.of(0.0, 1.0), 96.5);
        String json = tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(ChannelSnapshotViews.viewOf(stored, point));
        assertTrue(json.contains("\"annotations\":{}"), "标注缺失应输出 {} 而不是 null");
        assertNull(stored.getAnnotations(), "实体层保留 null 以便惰性补算");
    }

    private PointEntity point(String modalityType, String pointCode) {
        PointEntity point = mock(PointEntity.class);
        DeviceEntity device = mock(DeviceEntity.class);
        when(device.getDeviceCode()).thenReturn("BREAKER_10KV_02");
        when(point.getId()).thenReturn(7L);
        when(point.getDevice()).thenReturn(device);
        when(point.getPointCode()).thenReturn(pointCode);
        when(point.getModalityType()).thenReturn(modalityType);
        when(point.getSamplingRate()).thenReturn(null);
        when(point.getEngineeringUnit()).thenReturn("A");
        return point;
    }
}
