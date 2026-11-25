package com.pdm.mvp.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.json.JsonMapper;

/** 协议 2.1.0 MQTT 路由：主题-载荷一致性校验，不匹配即告警丢弃且不进入服务。 */
class PhmTopicIdentityTest {
    private static final String PAYLOAD = """
            {"protocol_version": "2.1.0", "timestamp": 1732262400000,
             "asset_id": "BREAKER_10KV_02", "channel_id": "CH_TRIP_UNIT",
             "modality_type": "SWITCHGEAR_EVENT",
             "metrics": {"trip_duration_ms": 42.5, "peak_coil_current_a": 5.42}}
            """;
    private final TelemetryService telemetryService = mock(TelemetryService.class);
    private final MqttTelemetryConsumer consumer = new MqttTelemetryConsumer(telemetryService, new JsonMapper());

    @Test
    void routesMatchingPhm21TopicToService() {
        consumer.receive(message("phm/telemetry/BREAKER_10KV_02/CH_TRIP_UNIT", PAYLOAD));

        ArgumentCaptor<PhmTelemetryRequest> captor = ArgumentCaptor.forClass(PhmTelemetryRequest.class);
        verify(telemetryService).accept(captor.capture(), eq("MQTT"));
        assertEquals("BREAKER_10KV_02", captor.getValue().assetId());
        assertEquals("CH_TRIP_UNIT", captor.getValue().channelId());
    }

    @Test
    void dropsPayloadWhenTopicIdentityMismatch() {
        consumer.receive(message("phm/telemetry/OTHER_ASSET/CH_OTHER", PAYLOAD));
        verifyNoInteractions(telemetryService);
    }

    @Test
    void dropsMalformedPayload() {
        consumer.receive(message("phm/telemetry/BREAKER_10KV_02/CH_TRIP_UNIT", "{not-json"));
        verifyNoInteractions(telemetryService);
    }

    private Message<String> message(String topic, String payload) {
        return MessageBuilder.withPayload(payload)
                .setHeader(org.springframework.integration.mqtt.support.MqttHeaders.RECEIVED_TOPIC, topic)
                .build();
    }
}
