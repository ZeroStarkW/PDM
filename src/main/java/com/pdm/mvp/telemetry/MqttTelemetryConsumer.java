package com.pdm.mvp.telemetry;

import com.pdm.mvp.snapshot.PhmSnapshotPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.util.Locale;

public class MqttTelemetryConsumer {
    private static final Logger log = LoggerFactory.getLogger(MqttTelemetryConsumer.class);
    static final String V1_PREFIX = "pdm/v1/";
    static final String V2_PREFIX = "pdm/v2/";
    static final String PHM_PREFIX = "devices/";
    static final String PHM21_PREFIX = "phm/telemetry/";
    static final String SNAPSHOT_PREFIX = "phm/snapshot/";
    private static final String RAW_SUFFIX = "/raw_waveform";
    private final TelemetryService telemetryService;
    private final JsonMapper mapper;
    /** 消费路由前缀：由配置的订阅主题推导（见 routingPrefix），默认值与内置主题一一对应。 */
    private final String v1Prefix;
    private final String v2Prefix;
    private final String phmPrefix;
    private final String phm21Prefix;
    private final String snapshotPrefix;

    public MqttTelemetryConsumer(TelemetryService telemetryService, JsonMapper mapper) {
        this(telemetryService, mapper, V1_PREFIX, V2_PREFIX, PHM_PREFIX, PHM21_PREFIX, SNAPSHOT_PREFIX);
    }

    MqttTelemetryConsumer(TelemetryService telemetryService, JsonMapper mapper, String v1Prefix, String v2Prefix,
            String phmPrefix, String phm21Prefix, String snapshotPrefix) {
        this.telemetryService = telemetryService; this.mapper = mapper;
        this.v1Prefix = v1Prefix; this.v2Prefix = v2Prefix; this.phmPrefix = phmPrefix;
        this.phm21Prefix = phm21Prefix; this.snapshotPrefix = snapshotPrefix;
    }

    /**
     * 由订阅主题推导消费路由前缀：在首个 +/# 通配段处截断（如 pdm/v1/+/+/+/telemetry → pdm/v1/）。
     * 无通配段取整串加尾斜杠；主题为空回退内置常量，保证自定义主题不会落回旧分支。
     */
    static String routingPrefix(String topic, String fallback) {
        if (topic == null || topic.isBlank()) return fallback;
        StringBuilder prefix = new StringBuilder();
        for (String segment : topic.split("/", -1)) {
            if ("+".equals(segment) || "#".equals(segment)) break;
            prefix.append(segment).append('/');
        }
        String derived = prefix.toString();
        return derived.isBlank() ? fallback : derived;
    }

    @ServiceActivator(inputChannel = "pdmMqttInput")
    public void receive(Message<String> message) {
        String topic = message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC, String.class);
        if (topic == null) return;
        if (topic.startsWith(v2Prefix)) {
            acceptContract(topic, message.getPayload());
        } else if (topic.startsWith(phm21Prefix)) {
            acceptPhm21(topic, message.getPayload());
        } else if (topic.startsWith(snapshotPrefix)) {
            acceptSnapshot(topic, message.getPayload());
        } else if (topic.startsWith(phmPrefix) && topic.endsWith(RAW_SUFFIX)) {
            acceptRawWaveform(topic, message.getPayload());
        } else if (topic.startsWith(phmPrefix)) {
            acceptProtocol(topic, message.getPayload());
        } else {
            acceptLegacy(topic, message.getPayload());
        }
    }

    /** 协议 2.1.0 快速事件快照：phm/snapshot/{asset_id}/{channel_id}，消费回调由快照模块装配期注入。 */
    private void acceptSnapshot(String topic, String payload) {
        if (snapshotConsumer == null) {
            log.warn("Snapshot received on {} but snapshot ingestion is not configured", topic);
            return;
        }
        PhmSnapshotPayload request;
        try {
            request = mapper.readValue(payload, PhmSnapshotPayload.class);
            verifySnapshotTopic(topic, request);
        } catch (tools.jackson.core.JacksonException | MessagingException exception) {
            log.warn("Rejected MQTT PHM 2.1 snapshot from topic {}: {}", topic, exception.getMessage());
            return;
        }
        dispatch(topic, () -> snapshotConsumer.accept(topic, payload));
    }

    private void verifySnapshotTopic(String topic, com.pdm.mvp.snapshot.PhmSnapshotPayload request) {
        if (request == null) throw new MessagingException("Empty MQTT snapshot payload");
        String expected = snapshotPrefix + identity(request.assetId(), "asset_id") + "/"
                + identity(request.channelId(), "channel_id");
        if (!expected.equalsIgnoreCase(topic)) throw new MessagingException("Topic and payload identity do not match");
    }

    /** 快照消费回调：与波形回调同构（topic, payload），保持本类与快照包解耦。 */
    private java.util.function.BiConsumer<String, String> snapshotConsumer;

    public void setSnapshotConsumer(java.util.function.BiConsumer<String, String> consumer) {
        this.snapshotConsumer = consumer;
    }

    /** 协议 2.1.0 慢周期特征遥测：phm/telemetry/{asset_id}/{channel_id}。 */
    private void acceptPhm21(String topic, String payload) {
        PhmTelemetryRequest request;
        try {
            request = mapper.readValue(payload, PhmTelemetryRequest.class);
            verifyPhm21Topic(topic, request);
        } catch (tools.jackson.core.JacksonException | MessagingException exception) {
            log.warn("Rejected MQTT PHM 2.1 telemetry from topic {}: {}", topic, exception.getMessage());
            return;
        }
        dispatch(topic, () -> telemetryService.accept(request, "MQTT"));
    }

    private void verifyPhm21Topic(String topic, PhmTelemetryRequest request) {
        if (request == null) throw new MessagingException("Empty MQTT telemetry payload");
        String expected = phm21Prefix + identity(request.assetId(), "asset_id") + "/"
                + identity(request.channelId(), "channel_id");
        if (!expected.equalsIgnoreCase(topic)) throw new MessagingException("Topic and payload identity do not match");
    }

    /**
     * 载荷身份字段：缺失或空白即以 MessagingException 拒绝（告警日志后丢弃）。
     * 这里的任何空值都不得放行：逃逸出 {@link #dispatch} 的运行时异常会使 Paho 在 QoS1 确认前断开连接，
     * 同一条毒消息在每次重连后重投，进而拖停全部 MQTT 接入（含旧振动主题）。
     */
    private static String identity(String value, String field) {
        if (value == null || value.isBlank()) throw new MessagingException(field + " is required");
        return value.trim().toUpperCase(Locale.ROOT);
    }

    /** 协议 2.0.0 慢遥测：devices/{asset_id}/{point_id}/telemetry。 */
    private void acceptProtocol(String topic, String payload) {
        ProtocolTelemetryRequest request;
        try {
            request = mapper.readValue(payload, ProtocolTelemetryRequest.class);
            verifyProtocolTopic(topic, request);
        } catch (tools.jackson.core.JacksonException | MessagingException exception) {
            log.warn("Rejected MQTT protocol telemetry from topic {}: {}", topic, exception.getMessage());
            return;
        }
        dispatch(topic, () -> telemetryService.accept(request, "MQTT"));
    }

    /** 协议 2.0.0 原始波形快照：devices/{asset_id}/{point_id}/raw_waveform。 */
    private void acceptRawWaveform(String topic, String payload) {
        if (rawWaveformConsumer == null) {
            log.warn("Raw waveform received on {} but waveform ingestion is not configured", topic);
            return;
        }
        try {
            rawWaveformConsumer.accept(topic, payload);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                log.warn("Rejected raw waveform from topic {}: {}", topic, exception.getReason());
                return;
            }
            throw exception;
        } catch (RuntimeException exception) {
            log.error("Failed to store raw waveform from topic {}: {}", topic, exception.getMessage());
        }
    }

    /** 波形消费回调由波形模块在装配期注入（保持本类与波形包解耦）。 */
    private java.util.function.BiConsumer<String, String> rawWaveformConsumer;

    public void setRawWaveformConsumer(java.util.function.BiConsumer<String, String> consumer) {
        this.rawWaveformConsumer = consumer;
    }

    private void verifyProtocolTopic(String topic, ProtocolTelemetryRequest request) {
        if (request == null) throw new MessagingException("Empty MQTT telemetry payload");
        String expected = phmPrefix + identity(request.assetId(), "asset_id") + "/"
                + identity(request.pointId(), "point_id") + "/telemetry";
        if (!expected.equalsIgnoreCase(topic)) throw new MessagingException("Topic and payload identity do not match");
    }

    /** 旧振动主题：载荷结构不变，进入适配器路径。 */
    private void acceptLegacy(String topic, String payload) {
        TelemetryRequest request;
        try {
            request = mapper.readValue(payload, TelemetryRequest.class);
            verifyLegacyTopic(topic, request);
        } catch (tools.jackson.core.JacksonException | MessagingException exception) {
            log.warn("Rejected MQTT telemetry from topic {}: {}", topic, exception.getMessage());
            return;
        }
        dispatch(topic, () -> telemetryService.accept(request, "MQTT"));
    }

    /** 统一契约主题：带模态类型与工况的版本化载荷。 */
    private void acceptContract(String topic, String payload) {
        ContractTelemetryRequest request;
        try {
            request = mapper.readValue(payload, ContractTelemetryRequest.class);
            verifyContractTopic(topic, request);
        } catch (tools.jackson.core.JacksonException | MessagingException exception) {
            log.warn("Rejected MQTT contract telemetry from topic {}: {}", topic, exception.getMessage());
            return;
        }
        dispatch(topic, () -> telemetryService.accept(request.toEnvelope(), "MQTT"));
    }

    private void dispatch(String topic, Runnable action) {
        try {
            action.run();
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                log.warn("Rejected invalid MQTT telemetry from topic {}: {}", topic, exception.getReason());
                return;
            }
            throw exception;
        }
    }

    private void verifyLegacyTopic(String topic, TelemetryRequest request) {
        if (request == null) throw new MessagingException("Empty MQTT telemetry payload");
        String expected = v1Prefix + identity(request.factoryId(), "factory_id") + "/"
                + identity(request.deviceCode(), "device_code") + "/" + identity(request.pointCode(), "point_code")
                + "/telemetry";
        if (!expected.equalsIgnoreCase(topic)) throw new MessagingException("Topic and payload identity do not match");
    }

    /** 统一契约主题：MQTT 路径没有 Bean Validation，timestamp / data_quality 缺失必须在拆箱前拒绝。 */
    private void verifyContractTopic(String topic, ContractTelemetryRequest request) {
        if (request == null) throw new MessagingException("Empty MQTT contract telemetry payload");
        if (request.timestamp() == null || request.dataQuality() == null)
            throw new MessagingException("timestamp and data_quality are required");
        String expected = v2Prefix + identity(request.factoryId(), "factory_id") + "/"
                + identity(request.deviceCode(), "device_code") + "/" + identity(request.pointCode(), "point_code")
                + "/telemetry";
        if (!expected.equalsIgnoreCase(topic)) throw new MessagingException("Topic and payload identity do not match");
    }
}
