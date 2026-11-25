package com.pdm.mvp.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * MQTT 消费路由前缀（B12）：由配置的订阅主题推导（首个通配段截断），自定义主题不落回旧分支；
 * 用户名/密码只配其一视为匿名不 NPE（配置侧见 MqttTelemetryConfiguration）。
 */
class MqttRoutingPrefixTest {

    @Test
    void routingPrefixCutsAtFirstWildcardSegment() {
        assertEquals("pdm/v1/", MqttTelemetryConsumer.routingPrefix("pdm/v1/+/+/+/telemetry", "fb/"));
        assertEquals("pdm/v2/", MqttTelemetryConsumer.routingPrefix("pdm/v2/+/+/+/telemetry", "fb/"));
        assertEquals("devices/", MqttTelemetryConsumer.routingPrefix("devices/+/+/telemetry", "fb/"));
        assertEquals("phm/telemetry/", MqttTelemetryConsumer.routingPrefix("phm/telemetry/+/+", "fb/"));
        assertEquals("phm/snapshot/", MqttTelemetryConsumer.routingPrefix("phm/snapshot/+/+", "fb/"));
        // 无通配段：整串加尾斜杠
        assertEquals("custom/topic/", MqttTelemetryConsumer.routingPrefix("custom/topic", "fb/"));
        // 空主题回退内置常量
        assertEquals("fb/", MqttTelemetryConsumer.routingPrefix(null, "fb/"));
        assertEquals("fb/", MqttTelemetryConsumer.routingPrefix("  ", "fb/"));
        // 首段即通配：前缀为空 → 回退
        assertEquals("fb/", MqttTelemetryConsumer.routingPrefix("+/x", "fb/"));
    }

    @Test
    void customPhm21PrefixRoutesToProtocolHandlerNotLegacyBranch() {
        // 自定义 2.1.0 主题前缀 iot/telemetry/：消息进 2.1.0 处理器，绝不落入旧振动分支
        TelemetryService telemetryService = mock(TelemetryService.class);
        MqttTelemetryConsumer consumer = new MqttTelemetryConsumer(telemetryService, new JsonMapper(),
                "pdm/v1/", "pdm/v2/", "devices/", "iot/telemetry/", "iot/snapshot/");
        String payload = "{\"protocol_version\":\"2.1.0\",\"timestamp\":1732262400000,"
                + "\"asset_id\":\"BREAKER_10KV_02\",\"channel_id\":\"CH_TRIP_UNIT\","
                + "\"metrics\":{\"trip_duration_ms\":42.5}}";
        Message<String> message = MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "iot/telemetry/BREAKER_10KV_02/CH_TRIP_UNIT")
                .build();

        assertDoesNotThrow(() -> consumer.receive(message));

        verify(telemetryService).accept(any(PhmTelemetryRequest.class), any(String.class));
        verify(telemetryService, never()).accept(any(TelemetryRequest.class), any(String.class));
        verify(telemetryService, never()).accept(any(ProtocolTelemetryRequest.class), any(String.class));
    }

    @Test
    void customPrefixMismatchFallsNowhereUnexpected() {
        // 主题不在任何派生前缀内 → 旧振动分支兜底（保持既有路由语义）
        TelemetryService telemetryService = mock(TelemetryService.class);
        MqttTelemetryConsumer consumer = new MqttTelemetryConsumer(telemetryService, new JsonMapper(),
                "pdm/v1/", "pdm/v2/", "devices/", "iot/telemetry/", "iot/snapshot/");
        String payload = "{\"factory_id\":\"F\",\"device_code\":\"D\",\"point_code\":\"P\","
                + "\"timestamp\":1732262400000,\"data_quality\":0,"
                + "\"telemetry\":{\"v_rms\":1.0,\"a_peak\":1.0,\"a_kurtosis\":3.0,\"freq_1x_amp\":0.5,\"freq_2x_amp\":0.1}}";
        Message<String> message = MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "pdm/v1/F/D/P/telemetry")
                .build();

        consumer.receive(message);

        verify(telemetryService).accept(any(TelemetryRequest.class), any(String.class));
        // 该消息不落入 2.1.0 分支（自定义前缀不回退旧常量分支的反向验证）
        verify(telemetryService, never()).accept(any(PhmTelemetryRequest.class), any(String.class));
    }
}
