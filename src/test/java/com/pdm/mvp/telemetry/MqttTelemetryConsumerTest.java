package com.pdm.mvp.telemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pdm.mvp.analytics.AnalyticsEngine;
import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.FactoryEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.modality.ModalityPluginRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * MQTT 接入健壮性：毒消息（缺身份/必填字段）必须告警丢弃而不是抛异常逃逸 —— 逃逸会让 Paho 在 QoS1 确认前断开，
 * 同一消息每次重连重投并拖停全部 MQTT 接入；主题与载荷身份比较不区分大小写。
 */
@ExtendWith(OutputCaptureExtension.class)
class MqttTelemetryConsumerTest {
    private static final String PHM21_TOPIC = "phm/telemetry/BREAKER_10KV_02/CH_TRIP_UNIT";
    private static final String PROTOCOL_TOPIC = "devices/BREAKER_10KV_02/CH_TRIP_UNIT/telemetry";
    private static final String BREAKER_METRICS = "\"metrics\":{\"trip_duration_ms\":42.5,\"peak_coil_current_a\":5.42}";
    private static final String VIBRATION_METRICS =
            "\"metrics\":{\"v_rms\":0.485,\"a_peak\":1.25,\"a_kurtosis\":3.02,\"feat_1x\":0.452,\"feat_2x\":0.118}";

    private final TelemetryService telemetryService = mock(TelemetryService.class);
    private final MqttTelemetryConsumer consumer = new MqttTelemetryConsumer(telemetryService, new JsonMapper());

    /** 2.1.0 消息缺 asset_id / channel_id（或为空白）：告警丢弃，服务不得被调用，也不得抛异常。 */
    @Test
    void phm21MessageMissingIdentityIsDroppedWithWarn(CapturedOutput output) {
        assertDoesNotThrow(() -> consumer.receive(message(PHM21_TOPIC, phm21(null, "CH_TRIP_UNIT"))));
        assertDoesNotThrow(() -> consumer.receive(message(PHM21_TOPIC, phm21("BREAKER_10KV_02", null))));
        assertDoesNotThrow(() -> consumer.receive(message(PHM21_TOPIC, phm21("BREAKER_10KV_02", "   "))));

        verifyNoInteractions(telemetryService);
        String rejected = "Rejected MQTT PHM 2.1 telemetry from topic " + PHM21_TOPIC;
        assertEquals(3, output.getOut().split(rejected, -1).length - 1, "每条毒消息都应有一条告警日志: " + output.getOut());
        assertTrue(output.getOut().contains("asset_id is required"));
        assertEquals(2, output.getOut().split("channel_id is required", -1).length - 1);
    }

    /** 2.0.0 消息缺 asset_id / point_id：同样的潜在空指针，同样告警丢弃。 */
    @Test
    void protocol20MessageMissingIdentityIsDroppedWithWarn(CapturedOutput output) {
        assertDoesNotThrow(() -> consumer.receive(message(PROTOCOL_TOPIC, protocol20(null, "CH_TRIP_UNIT", true))));
        assertDoesNotThrow(() -> consumer.receive(message(PROTOCOL_TOPIC, protocol20("BREAKER_10KV_02", null, true))));

        verifyNoInteractions(telemetryService);
        String rejected = "Rejected MQTT protocol telemetry from topic " + PROTOCOL_TOPIC;
        assertEquals(2, output.getOut().split(rejected, -1).length - 1, output.getOut());
        assertTrue(output.getOut().contains("asset_id is required"));
        assertTrue(output.getOut().contains("point_id is required"));
    }

    /** 2.0.0 消息缺 data_quality：MQTT 路径没有 Bean Validation，必须得到干净的 400（告警丢弃），而不是 toEnvelope 拆箱 NPE。 */
    @Test
    void protocol20MessageWithoutDataQualityIsRejectedAs400NotNpe(CapturedOutput output) {
        AssetLookup assets = mock(AssetLookup.class);
        when(assets.requirePointByDeviceAndPoint(any(), any())).thenReturn(point());
        var service = new TelemetryService(assets,
                new TelemetryValidator(Clock.fixed(Instant.ofEpochMilli(1732262400000L), ZoneOffset.UTC)),
                mock(com.pdm.mvp.timeseries.TelemetryStore.class), mock(AnalyticsEngine.class), mock(PointRepository.class),
                mock(com.pdm.mvp.alert.AlertService.class), new ModalityPluginRegistry(List.of()));
        var live = new MqttTelemetryConsumer(service, new JsonMapper());

        assertDoesNotThrow(() -> live.receive(message(PROTOCOL_TOPIC, protocol20("BREAKER_10KV_02", "CH_TRIP_UNIT", false))));

        assertTrue(output.getOut().contains("Rejected invalid MQTT telemetry from topic " + PROTOCOL_TOPIC),
                "缺 data_quality 应按 4xx 告警丢弃: " + output.getOut());
        assertTrue(output.getOut().contains("data_quality 为必填字段"), output.getOut());

        // REST 之外直接调用同样得到 400
        var request = new JsonMapper().readValue(protocol20("BREAKER_10KV_02", "CH_TRIP_UNIT", false), ProtocolTelemetryRequest.class);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.accept(request, "MQTT"));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("timestamp 和 data_quality 为必填字段", exception.getReason());

        var timeless = new JsonMapper().readValue("{\"protocol_version\":\"2.0.0\",\"asset_id\":\"BREAKER_10KV_02\","
                + "\"point_id\":\"CH_TRIP_UNIT\",\"data_quality\":0," + VIBRATION_METRICS + "}", ProtocolTelemetryRequest.class);
        assertEquals("timestamp 和 data_quality 为必填字段",
                assertThrows(ResponseStatusException.class, () -> service.accept(timeless, "MQTT")).getReason());
    }

    /** pdm/v2 统一契约主题：缺 timestamp / data_quality 在拆箱前丢弃。 */
    @Test
    void contractMessageWithoutTimestampOrDataQualityIsDropped(CapturedOutput output) {
        String contractTopic = "pdm/v2/FACT_01/BREAKER_10KV_02/CH_TRIP_UNIT/telemetry";
        String contract = """
                ,"modality_type":"SWITCHGEAR_EVENT","factory_id":"FACT_01","device_code":"BREAKER_10KV_02",
                 "point_code":"CH_TRIP_UNIT","features":{"trip_duration_ms":42.5,"peak_coil_current_a":5.42}}
                """;
        assertDoesNotThrow(() -> consumer.receive(message(contractTopic,
                "{\"contract_version\":2" + contract)));
        assertDoesNotThrow(() -> consumer.receive(message(contractTopic,
                "{\"contract_version\":2,\"timestamp\":1732262400000" + contract)));

        verifyNoInteractions(telemetryService);
        assertEquals(2, output.getOut().split("timestamp and data_quality are required", -1).length - 1, output.getOut());
    }

    /** 主题里的资产/通道为小写、载荷为大写：测点定位本就不区分大小写，主题一致性比较也不得因此丢弃。 */
    @Test
    void topicIdentityIsCaseInsensitiveForPhmTopics() {
        String valve = "{\"protocol_version\":\"2.1.0\",\"timestamp\":1732262400000,\"asset_id\":\"FCV101\","
                + "\"channel_id\":\"CH_VALVE_LOOP\",\"modality_type\":\"CONTROL_VALVE_PROCESS\","
                + "\"metrics\":{\"hysteresis_deadband_pct\":2.15,\"ultrasonic_leak_rms\":0.04}}";
        consumer.receive(message("phm/telemetry/fcv101/ch_valve_loop", valve));
        consumer.receive(message("phm/telemetry/FCV101/CH_VALVE_LOOP", valve));
        consumer.receive(message("phm/telemetry/FCV101/ch_valve_loop", valve));
        consumer.receive(message("phm/telemetry/FCV101/CH_VALVE_LOOP",
                valve.replace("\"FCV101\"", "\" FCV101 \"").replace("\"CH_VALVE_LOOP\"", "\"CH_VALVE_LOOP \"")));
        consumer.receive(message("devices/pump_system_01/pump01_mtr_de_radial/telemetry",
                protocol20("PUMP_SYSTEM_01", "PUMP01_MTR_DE_RADIAL", true)));

        ArgumentCaptor<PhmTelemetryRequest> phm21 = ArgumentCaptor.forClass(PhmTelemetryRequest.class);
        verify(telemetryService, times(4)).accept(phm21.capture(), org.mockito.ArgumentMatchers.eq("MQTT"));
        // 载荷标识原样进入记录（空白在主题一致性校验与服务定位时才修剪）
        assertTrue(phm21.getAllValues().stream().allMatch(request -> request.assetId().trim().equals("FCV101")
                && request.channelId().trim().equals("CH_VALVE_LOOP")));
        ArgumentCaptor<ProtocolTelemetryRequest> protocol = ArgumentCaptor.forClass(ProtocolTelemetryRequest.class);
        verify(telemetryService).accept(protocol.capture(), org.mockito.ArgumentMatchers.eq("MQTT"));
        assertEquals("PUMP01_MTR_DE_RADIAL", protocol.getValue().pointId());
    }

    @Test
    void mismatchedTopicIdentityIsStillDropped(CapturedOutput output) {
        consumer.receive(message("phm/telemetry/fcv101/ch_valve_loop", phm21("FCV102", "CH_VALVE_LOOP")));
        consumer.receive(message("phm/telemetry/FCV101/CH_VALVE_LOOP", phm21("FCV101", "CH_OTHER_LOOP")));

        verifyNoInteractions(telemetryService);
        assertEquals(2, output.getOut().split("Topic and payload identity do not match", -1).length - 1,
                "身份不一致仍必须丢弃");
    }

    /** 2.1.0 快照主题：身份缺失丢弃；大小写不一致但身份一致时照常交给快照服务。 */
    @Test
    void snapshotTopicIdentityIsCaseInsensitiveAndRequiresIdentity() {
        AtomicReference<String> forwarded = new AtomicReference<>();
        consumer.setSnapshotConsumer((topic, payload) -> forwarded.set(topic));

        consumer.receive(message("phm/snapshot/breaker_10kv_02/ch_trip_unit", """
                {"protocol_version":"2.1.0","timestamp":1732262400000,"asset_id":"BREAKER_10KV_02",
                 "channel_id":"CH_TRIP_UNIT","trigger_type":"ON_DEMAND","preview_curve":[0.0,1.2,3.5]}
                """));
        assertEquals("phm/snapshot/breaker_10kv_02/ch_trip_unit", forwarded.get(), "小写主题应原样转交快照服务");

        forwarded.set(null);
        consumer.receive(message("phm/snapshot/BREAKER_10KV_02/CH_TRIP_UNIT", """
                {"protocol_version":"2.1.0","timestamp":1732262400000,"trigger_type":"ON_DEMAND","preview_curve":[0.0,1.2]}
                """));
        assertNull(forwarded.get(), "缺 asset_id/channel_id 的快照必须丢弃");
    }

    /** 2.1.0 慢遥测载荷；asset_id / channel_id 传 null 表示该字段缺失。 */
    private static String phm21(String assetId, String channelId) {
        return "{\"protocol_version\":\"2.1.0\",\"timestamp\":1732262400000,"
                + (assetId == null ? "" : "\"asset_id\":\"" + assetId + "\",")
                + (channelId == null ? "" : "\"channel_id\":\"" + channelId + "\",")
                + "\"modality_type\":\"SWITCHGEAR_EVENT\"," + BREAKER_METRICS + "}";
    }

    /** 2.0.0 慢遥测载荷；asset_id / point_id 传 null 表示该字段缺失，withQuality 控制 data_quality 是否出现。 */
    private static String protocol20(String assetId, String pointId, boolean withQuality) {
        return "{\"protocol_version\":\"2.0.0\",\"timestamp\":1732262400000,"
                + (assetId == null ? "" : "\"asset_id\":\"" + assetId + "\",")
                + (pointId == null ? "" : "\"point_id\":\"" + pointId + "\",")
                + (withQuality ? "\"data_quality\":0," : "") + VIBRATION_METRICS + "}";
    }

    private static PointEntity point() {
        var device = new DeviceEntity(new FactoryEntity("FACT_01", "一号工厂"), "BREAKER_10KV_02", "10kV进线开关",
                null, null, null);
        PointEntity point = new PointEntity(device, "CH_TRIP_UNIT", "分闸特性", null);
        point.setModalityType("SWITCHGEAR_EVENT");
        return point;
    }

    private static Message<String> message(String topic, String payload) {
        return MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.RECEIVED_TOPIC, topic)
                .build();
    }
}
