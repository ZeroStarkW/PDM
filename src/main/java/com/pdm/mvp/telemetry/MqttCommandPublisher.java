package com.pdm.mvp.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * MQTT 下行指令发布门面：MQTT 未启用时 {@link #publish} 返回 false，调用方以 409 拒绝召唤。
 */
@Component
public class MqttCommandPublisher {
    private static final Logger log = LoggerFactory.getLogger(MqttCommandPublisher.class);
    private final MessageChannel outbound;

    public MqttCommandPublisher(@Qualifier("mqttOutboundChannel") ObjectProvider<MessageChannel> outbound) {
        this.outbound = outbound.getIfAvailable();
    }

    public boolean enabled() { return outbound != null; }

    public boolean publish(String topic, String payload) {
        if (outbound == null) return false;
        Message<String> message = MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.TOPIC, topic)
                .build();
        try {
            outbound.send(message);
            return true;
        } catch (RuntimeException exception) {
            log.warn("MQTT 指令发布失败 topic={}: {}", topic, exception.getMessage());
            return false;
        }
    }
}
