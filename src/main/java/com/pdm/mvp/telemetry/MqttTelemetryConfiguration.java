package com.pdm.mvp.telemetry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.MessageChannel;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.config.EnableIntegration;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ConfigurationProperties("pdm.mqtt")
record MqttProperties(boolean enabled, String url, String clientId, String topic, String v2Topic,
        String phmTopic, String phmRawTopic, String phm21Topic, String phmSnapshotTopic,
        String username, String password) {}

@Configuration
@EnableIntegration
@EnableConfigurationProperties(MqttProperties.class)
@ConditionalOnProperty(prefix = "pdm.mqtt", name = "enabled", havingValue = "true")
class MqttTelemetryConfiguration {
    @Bean(destroyMethod = "shutdown")
    ExecutorService mqttCallbackExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "pdm-mqtt-subscribe");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    MessageChannel pdmMqttInput() { return new DirectChannel(); }

    @Bean
    MqttPahoMessageDrivenChannelAdapter mqttInbound(MqttProperties properties, MessageChannel pdmMqttInput,
                                                     ExecutorService mqttCallbackExecutor) {
        var options = new MqttConnectOptions();
        options.setServerURIs(new String[]{properties.url()});
        options.setAutomaticReconnect(true);
        options.setCleanSession(false);
        // 用户名与密码必须同时配置：只配用户名不配密码视为匿名（此前 NPE 启动失败）
        if (properties.username() != null && !properties.username().isBlank()
                && properties.password() != null && !properties.password().isBlank()) {
            options.setUserName(properties.username());
            options.setPassword(properties.password().toCharArray());
        }
        var clientFactory = new DefaultMqttPahoClientFactory();
        clientFactory.setConnectionOptions(options);
        // 同时订阅旧振动主题（pdm/v1）、统一契约主题（pdm/v2）、PHM 协议 2.0.0 主题（devices/...）、
        // 2.1.0 慢遥测主题（phm/telemetry/...）与 2.1.0 快速事件快照主题（phm/snapshot/...）
        var adapter = new MqttPahoMessageDrivenChannelAdapter(properties.url(), properties.clientId(), clientFactory,
                properties.topic(), properties.v2Topic(),
                properties.phmTopic() == null ? "devices/+/+/telemetry" : properties.phmTopic(),
                properties.phmRawTopic() == null ? "devices/+/+/raw_waveform" : properties.phmRawTopic(),
                properties.phm21Topic() == null ? "phm/telemetry/+/+" : properties.phm21Topic(),
                properties.phmSnapshotTopic() == null ? "phm/snapshot/+/+" : properties.phmSnapshotTopic()) {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                mqttCallbackExecutor.execute(() -> super.connectComplete(reconnect, serverURI));
            }
        };
        adapter.setOutputChannel(pdmMqttInput);
        adapter.setQos(1);
        adapter.setConverter(new DefaultPahoMessageConverter());
        return adapter;
    }

    @Bean
    MqttTelemetryConsumer mqttTelemetryConsumer(TelemetryService telemetryService, JsonMapper mapper,
            MqttProperties properties,
            org.springframework.beans.factory.ObjectProvider<com.pdm.mvp.waveform.WaveformService> waveformService,
            org.springframework.beans.factory.ObjectProvider<com.pdm.mvp.snapshot.ChannelSnapshotService> snapshotService) {
        // 消费路由前缀从配置的订阅主题推导（首个通配段截断），自定义主题不再依赖硬编码常量
        var consumer = new MqttTelemetryConsumer(telemetryService, mapper,
                MqttTelemetryConsumer.routingPrefix(properties.topic(), MqttTelemetryConsumer.V1_PREFIX),
                MqttTelemetryConsumer.routingPrefix(properties.v2Topic(), MqttTelemetryConsumer.V2_PREFIX),
                MqttTelemetryConsumer.routingPrefix(properties.phmTopic(), MqttTelemetryConsumer.PHM_PREFIX),
                MqttTelemetryConsumer.routingPrefix(properties.phm21Topic(), MqttTelemetryConsumer.PHM21_PREFIX),
                MqttTelemetryConsumer.routingPrefix(properties.phmSnapshotTopic(), MqttTelemetryConsumer.SNAPSHOT_PREFIX));
        com.pdm.mvp.waveform.WaveformService waveform = waveformService.getIfAvailable();
        if (waveform != null) consumer.setRawWaveformConsumer(waveform::ingest);
        com.pdm.mvp.snapshot.ChannelSnapshotService snapshot = snapshotService.getIfAvailable();
        if (snapshot != null) consumer.setSnapshotConsumer(snapshot::ingest);
        return consumer;
    }
}
