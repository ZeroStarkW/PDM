package com.pdm.mvp.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.snapshot.ChannelSnapshotViews.ChannelSnapshotView;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * v2 推流枢纽：具名事件按模态分发、帧契约键名与坏连接摘除（失败不影响其他订阅者）。
 * 推送已改为独立推流线程异步投递：单测注入直通执行器（Runnable::run）保持断言同步。
 */
class ModalityStreamHubTest {
    private final ModalityStreamHub hub = new ModalityStreamHub(new JsonMapper(), Runnable::run);
    private final SseEmitter first = mock(SseEmitter.class);
    private final SseEmitter second = mock(SseEmitter.class);
    private final SseEmitter otherChannel = mock(SseEmitter.class);

    private ChannelSnapshotView strokeView() {
        return new ChannelSnapshotView(12L, 7L, "CH_TRIP_UNIT", "BREAKER_10KV_02",
                ModalityTypes.SWITCHGEAR_EVENT, "TRIP_ACTION_BURST", 1732262400000L,
                "2024-11-22T00:00:00Z", 10000.0, 9, "A", "LOCAL_VOLUME",
                "file:/data/waveforms/7/1732262400000.json",
                "s3://phm-vault/x.bin", 96.5, List.of(0.0, 1.2),
                Map.of("coil_current_a", List.of(0.0, 0.8)),
                Map.of("coil_current_a", "A"),
                Map.of("trip_duration_ms", 42.5),
                Map.of("tripStartMs", 12.0, "peakCurrentA", 5.42));
    }

    @Test
    void snapshotFrameReachesOnlyChannelSubscribersWithStrokeEvent() throws IOException {
        hub.subscribe(7L, first);
        hub.subscribe(7L, second);
        hub.subscribe(8L, otherChannel);
        ChannelSnapshotView view = strokeView();
        hub.publishSnapshot(7L, view);
        verify(first).send(any(SseEmitter.SseEventBuilder.class));
        verify(second).send(any(SseEmitter.SseEventBuilder.class));
        verify(otherChannel, never()).send(any(SseEmitter.SseEventBuilder.class));
        assertEquals(ModalityStreamHub.EVENT_STROKE, ModalityStreamHub.eventName(ModalityTypes.SWITCHGEAR_EVENT));
        assertEquals(ModalityStreamHub.EVENT_VALVE, ModalityStreamHub.eventName(ModalityTypes.CONTROL_VALVE_PROCESS));
        assertEquals(ModalityStreamHub.EVENT_METRICS, ModalityStreamHub.eventName(ModalityTypes.MCSA));
        assertEquals(ModalityStreamHub.EVENT_METRICS, ModalityStreamHub.eventName(ModalityTypes.ROTATING_VIB));
    }

    @Test
    void snapshotFramePayloadCarriesContractKeys() {
        String payload = new JsonMapper().writeValueAsString(
                hub.buildSnapshotFrame(strokeView()));
        assertTrue(payload.contains("\"frame_type\":\"STROKE_EVENT_FRAME\""));
        assertTrue(payload.contains("\"modality_type\":\"SWITCHGEAR_EVENT\""));
        assertTrue(payload.contains("\"channel_code\":\"CH_TRIP_UNIT\""));
        assertTrue(payload.contains("\"curves\":"));
        assertTrue(payload.contains("\"curve_units\":"));
        assertTrue(payload.contains("\"annotations\"") && payload.contains("\"tripStartMs\":12.0"),
                "标注键为前端契约 camelCase（映射键序不敏感）");
    }

    @Test
    void metricsFramePayloadCarriesContractKeys() {
        Map<String, Object> frame = hub.buildMetricsFrame(1732262400000L, ModalityTypes.MCSA, "CH_MCSA",
                Map.of("sideband_db", 54.0), 91.0, "HEALTHY");
        assertEquals(List.of("frame_type", "timestamp", "modality_type", "channel_code",
                "metrics", "health_index", "alarm_level"), List.copyOf(frame.keySet()));
        assertEquals("METRICS_FRAME", frame.get("frame_type"));
        String payload = new JsonMapper().writeValueAsString(frame);
        assertTrue(payload.contains("\"alarm_level\":\"HEALTHY\""));
    }

    @Test
    void vibrationModalityIsNeverPublishedOnV2() throws IOException {
        hub.subscribe(7L, first);
        hub.publishMetrics(7L, Map.of("v_rms", 3.0), 90.0, "HEALTHY", ModalityTypes.ROTATING_VIB,
                "PUMP_DE_VIB", 1732262400000L);
        verify(first, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void brokenEmitterIsRemovedWithoutAffectingOthers() throws IOException {
        hub.subscribe(7L, first);
        hub.subscribe(7L, second);
        doThrow(new IOException("broken pipe")).when(first).send(any(SseEmitter.SseEventBuilder.class));
        hub.publishSnapshot(7L, strokeView());
        hub.publishSnapshot(7L, strokeView());
        // 失败连接仅收到一次推送尝试，其余订阅者持续收到帧
        verify(first, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(second, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void publishWithoutSubscribersIsQuietNoOp() {
        Map<String, Double> metrics = new LinkedHashMap<>();
        hub.publishMetrics(99L, metrics, null, null, ModalityTypes.MCSA, "CH_MCSA", 1L);
        hub.publishSnapshot(99L, strokeView());
        assertFalse(hub.hasSubscribers(99L));
    }

    @Test
    void throwingEmitterNeverPropagatesToCaller() throws IOException {
        // B5：慢/坏 emitter 的异常在推流路径内消化，绝不抛回遥测入库线程
        hub.subscribe(7L, first);
        doThrow(new RuntimeException("send timeout")).when(first).send(any(SseEmitter.SseEventBuilder.class));
        hub.publishMetrics(7L, Map.of("sideband_db", 54.0), 90.0, "HEALTHY", ModalityTypes.MCSA, "CH_MCSA", 1L);
        verify(first, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertFalse(hub.hasSubscribers(7L), "坏连接应被摘除");
    }

    @Test
    void metricsFrameCarriesOptionalStatusAndOmitsNullStatus() {
        // B6：学习期/门禁帧携带 status；已评分帧不输出 status 键（契约保持精简）
        Map<String, Object> learning = hub.buildMetricsFrame(1L, ModalityTypes.MCSA, "CH_MCSA",
                Map.of("sideband_db", 54.0), null, null, "BASELINE_LEARNING");
        assertEquals("BASELINE_LEARNING", learning.get("status"));
        assertEquals(8, learning.size());
        Map<String, Object> scored = hub.buildMetricsFrame(1L, ModalityTypes.MCSA, "CH_MCSA",
                Map.of("sideband_db", 54.0), 91.0, "HEALTHY");
        assertFalse(scored.containsKey("status"));
        assertEquals(7, scored.size());
    }

    @Test
    void snapshotFrameEmitsEmptyMapForNullAnnotations() {
        // B12：annotations 契约键永不为 JSON null
        ChannelSnapshotView noAnnotations = new ChannelSnapshotView(12L, 7L, "CH_TRIP_UNIT", "BREAKER_10KV_02",
                ModalityTypes.SWITCHGEAR_EVENT, "SCHEDULED", 1L, "2024-11-22T00:00:00Z", 10000.0, 1, "A",
                "LOCAL_VOLUME", "file:/ref", null, 96.5, List.of(0.0), Map.of(), Map.of(), Map.of(), null);
        String payload = new JsonMapper().writeValueAsString(hub.buildSnapshotFrame(noAnnotations));
        assertTrue(payload.contains("\"annotations\":{}"), "标注缺失应输出 {} 而不是 null");
    }

    @Test
    void hubSurvivesConcurrentSubscribePublishUnsubscribe() throws InterruptedException {        // B5(d)：ConcurrentHashMap 订阅表并发读写冒烟（不抛异常、订阅簿最终一致）
        int threads = 8;
        var latch = new java.util.concurrent.CountDownLatch(threads);
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        for (int t = 0; t < threads; t++) {
            long pointId = 100L + t;
            Thread.ofPlatform().start(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        SseEmitter emitter = new SseEmitter(0L);
                        hub.subscribe(pointId, emitter);
                        hub.publishMetrics(pointId, Map.of("sideband_db", 1.0), 90.0, "HEALTHY",
                                ModalityTypes.MCSA, "CH_MCSA", i);
                        hub.unsubscribe(pointId, emitter);
                    }
                } catch (RuntimeException failure) {
                    failures.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, failures.get(), "并发订阅/推流/摘除必须无异常");
    }

    @Test
    void transactionalPublishIsDeferredUntilAfterCommit() throws IOException {
        // B5(b)：入库事务内推流延迟到 afterCommit（帧只反映已提交的数据），提交前不发送
        TransactionSynchronizationManager.initSynchronization();
        try {
            hub.subscribe(7L, first);
            hub.publishSnapshot(7L, strokeView());
            verify(first, never()).send(any(SseEmitter.SseEventBuilder.class));
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            verify(first).send(any(SseEmitter.SseEventBuilder.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
