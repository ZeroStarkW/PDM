package com.pdm.mvp.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pdm.mvp.telemetry.MqttTelemetryConsumer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 协议 2.1.0 快照载荷契约：解析、版本/触发/点阵校验与 MQTT 主题-载荷一致性路由。 */
class PhmSnapshotPayloadTest {
    private static final String BREAKER_PAYLOAD = """
            {"protocol_version":"2.1.0","timestamp":1732262400000,"asset_id":"BREAKER_10KV_02",
             "channel_id":"CH_TRIP_UNIT","modality_type":"SWITCHGEAR_EVENT","trigger_type":"TRIP_ACTION_BURST",
             "sampling_rate":10000,"points_count":1000,"storage_backend":"MINIO_DIRECT",
             "snapshot_url":"s3://phm-vault/20261122/BREAKER_10KV_02/1732262400000.bin",
             "preview_curve":[0.0,0.1,1.2,3.5,5.42,4.8,1.2,0.1,0.0],
             "curves":{"time_axis_ms":[0.0,5.0],"coil_current_a":[0.0,0.8],"travel_stroke_mm":[0.0,0.0]},
             "curve_units":{"coil_current_a":"A"},
             "metrics":{"trip_duration_ms":42.5},"health_index":96.5,"data_quality":0}
            """;
    private final JsonMapper mapper = new JsonMapper();

    private PhmSnapshotPayload parse(String json) {
        return mapper.readValue(json, PhmSnapshotPayload.class);
    }

    @Test
    void parsesDocumentedBreakerPayload() {
        PhmSnapshotPayload payload = parse(BREAKER_PAYLOAD);
        assertEquals("2.1.0", payload.protocolVersion());
        assertTrue(payload.isSupportedProtocol());
        assertEquals(1732262400000L, payload.timestamp());
        assertEquals("BREAKER_10KV_02", payload.assetId());
        assertEquals("CH_TRIP_UNIT", payload.channelId());
        assertEquals("SWITCHGEAR_EVENT", payload.modalityType());
        assertEquals("TRIP_ACTION_BURST", payload.triggerType());
        assertTrue(payload.isKnownTrigger());
        assertEquals(10000.0, payload.samplingRate(), 1e-9);
        assertEquals(List.of(0.0, 0.1, 1.2, 3.5, 5.42, 4.8, 1.2, 0.1, 0.0), payload.previewCurve());
        assertEquals("A", payload.curveUnits().get("coil_current_a"));
        assertEquals(42.5, payload.metrics().get("trip_duration_ms"), 1e-9);
        assertEquals(96.5, payload.healthIndex(), 1e-9);
        assertEquals("s3://phm-vault/20261122/BREAKER_10KV_02/1732262400000.bin", payload.edgeSnapshotUrl());
        assertEquals(9, payload.checkedPreview(16384).size());
        assertEquals(3, payload.checkedCurves().size());
        assertEquals(Map.of("trip_duration_ms", 42.5), payload.finiteMetrics());
    }

    @Test
    void parsesValveLoopVariant() {
        PhmSnapshotPayload payload = parse("""
                {"protocol_version":"2.1.0","timestamp":1732262400000,"asset_id":"VALVE_01",
                 "channel_id":"CH_VALVE_LOOP","trigger_type":"SCHEDULED","sampling_rate":1,
                 "preview_curve":[0.0,1.0],
                 "curves":{"cv_pct":[0.0,25.0,50.0],"fb_pct":[0.0,23.0,48.0]},
                 "curve_units":{"cv_pct":"%","fb_pct":"%"}}
                """);
        assertEquals(List.of(0.0, 25.0, 50.0), payload.checkedCurves().get("cv_pct"));
        assertEquals(List.of(0.0, 23.0, 48.0), payload.checkedCurves().get("fb_pct"));
        assertEquals("%", payload.curveUnits().get("fb_pct"));
    }

    @Test
    void rejectsUnsupportedProtocolVersion() {
        PhmSnapshotPayload payload = parse(BREAKER_PAYLOAD.replace("\"2.1.0\"", "\"2.0.0\""));
        assertFalse(payload.isSupportedProtocol());
        assertTrue(payload.isKnownTrigger());
    }

    @Test
    void rejectsUnknownTriggerType() {
        PhmSnapshotPayload payload = parse(BREAKER_PAYLOAD.replace("TRIP_ACTION_BURST", "MANUAL_TEST"));
        assertFalse(payload.isKnownTrigger());
    }

    @Test
    void enforcesPreviewSizeLimit() {
        PhmSnapshotPayload payload = parse(BREAKER_PAYLOAD.replace(
                "[0.0,0.1,1.2,3.5,5.42,4.8,1.2,0.1,0.0]",
                "[0.0,0.1,1.2,3.5,5.42,4.8,1.2,0.1,0.0,1.0,2.0,3.0]"));
        assertEquals(12, payload.checkedPreview(16384).size());
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> payload.checkedPreview(11));
        assertTrue(exception.getMessage().contains("上限"));
    }

    @Test
    void rejectsNonFiniteCurveValue() {
        PhmSnapshotPayload payload = new PhmSnapshotPayload("2.1.0", 1732262400000L, "A", "C", null,
                "ON_DEMAND", 1.0, null, null, null, List.of(0.0, Double.NaN),
                Map.of("cv_pct", List.of(1.0, Double.POSITIVE_INFINITY)), null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> payload.checkedPreview(16384));
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, payload::checkedCurves);
        assertTrue(exception.getMessage().contains("cv_pct"));
    }

    @Test
    void dropsNonFiniteMetricEntriesSilently() {
        PhmSnapshotPayload payload = new PhmSnapshotPayload("2.1.0", 1732262400000L, "A", "C", null,
                "ON_DEMAND", null, null, null, null, List.of(0.0), null, null,
                Map.of("ok", 1.5, "bad", Double.NEGATIVE_INFINITY), null, null);
        assertEquals(Map.of("ok", 1.5), payload.finiteMetrics());
    }

    @Test
    void consumerRoutesMatchingSnapshotTopicToCallback() {
        TelemetryRouteHarness harness = new TelemetryRouteHarness();
        harness.consumer.receive(message("phm/snapshot/BREAKER_10KV_02/CH_TRIP_UNIT", BREAKER_PAYLOAD));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(harness.snapshotConsumer).accept(eq("phm/snapshot/BREAKER_10KV_02/CH_TRIP_UNIT"), payload.capture());
        assertEquals(BREAKER_PAYLOAD, payload.getValue());
    }

    @Test
    void consumerDropsSnapshotTopicIdentityMismatch() {
        TelemetryRouteHarness harness = new TelemetryRouteHarness();
        harness.consumer.receive(message("phm/snapshot/OTHER_ASSET/CH_OTHER", BREAKER_PAYLOAD));
        verifyNoInteractions(harness.snapshotConsumer);
    }

    @Test
    void consumerDropsMalformedSnapshotPayload() {
        TelemetryRouteHarness harness = new TelemetryRouteHarness();
        harness.consumer.receive(message("phm/snapshot/BREAKER_10KV_02/CH_TRIP_UNIT", "{not-json"));
        verifyNoInteractions(harness.snapshotConsumer);
    }

    @Test
    void consumerSwallowsClientRejectionsFromService() {
        TelemetryRouteHarness harness = new TelemetryRouteHarness();
        org.mockito.Mockito.doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "trigger_type 只能是 TRIP_ACTION_BURST/THRESHOLD_EXCEEDED/SCHEDULED/ON_DEMAND"))
                .when(harness.snapshotConsumer).accept(any(), any());
        harness.consumer.receive(message("phm/snapshot/BREAKER_10KV_02/CH_TRIP_UNIT", BREAKER_PAYLOAD));
        // 4xx 由 dispatch 吞掉：不再上抛（服务端错误仍会上抛，这里仅验证 4xx 路径）
        verify(harness.snapshotConsumer).accept(any(), any());
    }

    /** 装配遥测消费者并注入快照回调（快照包与 telemetry 包的接缝单测）。 */
    private static final class TelemetryRouteHarness {
        final com.pdm.mvp.telemetry.TelemetryService telemetryService =
                mock(com.pdm.mvp.telemetry.TelemetryService.class);
        final MqttTelemetryConsumer consumer;
        final java.util.function.BiConsumer<String, String> snapshotConsumer =
                mock(java.util.function.BiConsumer.class);

        TelemetryRouteHarness() {
            consumer = new MqttTelemetryConsumer(telemetryService, new JsonMapper());
            consumer.setSnapshotConsumer(snapshotConsumer);
        }
    }

    private Message<String> message(String topic, String payload) {
        return MessageBuilder.withPayload(payload)
                .setHeader(org.springframework.integration.mqtt.support.MqttHeaders.RECEIVED_TOPIC, topic)
                .build();
    }
}
