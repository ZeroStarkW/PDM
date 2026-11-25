package com.pdm.mvp.telemetry;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.MessageChannel;

/**
 * MQTT 出站通道：与入站适配器共用 broker 配置但使用独立 clientId（避免会话互踢），
 * 供专家召唤波形等下行指令发布（文档 §2.2 ON_DEMAND）。
 */
@Configuration
@ConditionalOnProperty(prefix = "pdm.mqtt", name = "enabled", havingValue = "true")
class MqttOutboundConfiguration {

    @Bean
    MessageChannel mqttOutboundChannel() { return new DirectChannel(); }

    @Bean
    @ServiceActivator(inputChannel = "mqttOutboundChannel")
    MqttPahoMessageHandler mqttOutbound(MqttProperties properties) {
        var options = new MqttConnectOptions();
        options.setServerURIs(new String[]{properties.url()});
        options.setAutomaticReconnect(true);
        if (properties.username() != null && !properties.username().isBlank()
                && properties.password() != null && !properties.password().isBlank()) {
            options.setUserName(properties.username());
            options.setPassword(properties.password().toCharArray());
        }
        var clientFactory = new DefaultMqttPahoClientFactory();
        clientFactory.setConnectionOptions(options);
        var handler = new MqttPahoMessageHandler(properties.url(), properties.clientId() + "-out", clientFactory);
        handler.setAsync(true);
        handler.setDefaultQos(1);
        return handler;
    }
}
