package com.pdm.mvp.snapshot;

import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.snapshot.ChannelSnapshotViews.ChannelSnapshotView;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 多模态 v2 实时推流枢纽（文档 §6.2）：按测点维护 SSE 订阅者，仅具名事件推送
 * （metrics_frame / stroke_event_frame / valve_loop_frame）。振动通道由 v1 live-stream 推流，
 * 本枢纽永不推送，避免双推。所有 publish 失败安全：序列化或发送异常仅摘除坏连接并告警，
 * 且绝不向调用方（遥测入库线程）传播。
 * 推送在专用单线程执行器上序列化并发送（慢/坏连接不阻塞入库回调线程）；
 * 调用方处于活动事务时帧延迟到 afterCommit 投递（只推已提交的数据）。
 */
@Component
public class ModalityStreamHub {
    private static final Logger log = LoggerFactory.getLogger(ModalityStreamHub.class);
    public static final String EVENT_METRICS = "metrics_frame";
    public static final String EVENT_STROKE = "stroke_event_frame";
    public static final String EVENT_VALVE = "valve_loop_frame";

    private final JsonMapper mapper;
    private final Map<Long, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    /** 推流专用单线程（守护进程线程）：帧序列化与 SSE 发送全部在此线程执行。 */
    private final Executor publisher;

    /** 类里有两个构造器（下方为测试直通执行器），Spring 须显式指定装配入口。 */
    @org.springframework.beans.factory.annotation.Autowired
    public ModalityStreamHub(JsonMapper mapper) {
        this(mapper, Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "pdm-stream-publisher");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** 测试注入直通执行器（同步投递，便于断言）；生产构造默认走独立推流线程。 */
    ModalityStreamHub(JsonMapper mapper, Executor publisher) {
        this.mapper = mapper;
        this.publisher = publisher;
    }

    public void subscribe(Long pointId, SseEmitter emitter) {
        subscribers.computeIfAbsent(pointId, key -> ConcurrentHashMap.newKeySet()).add(emitter);
    }

    public void unsubscribe(Long pointId, SseEmitter emitter) {
        Set<SseEmitter> emitters = subscribers.get(pointId);
        if (emitters == null) return;
        emitters.remove(emitter);
        if (emitters.isEmpty()) subscribers.remove(pointId, emitters);
    }

    boolean hasSubscribers(Long pointId) {
        Set<SseEmitter> emitters = subscribers.get(pointId);
        return emitters != null && !emitters.isEmpty();
    }

    /** 遥测评估帧推送（TelemetryService 附加钩子调用）；振动模态直接忽略。 */
    public void publishMetrics(Long pointId, Map<String, Double> metrics, Double healthIndex, String alarmLevel,
            String modalityType, String channelCode, long timestamp) {
        publishMetrics(pointId, metrics, healthIndex, alarmLevel, modalityType, channelCode, timestamp, null);
    }

    /** 学习期/工况门禁帧：assessment 缺席时 health_index/alarm_level 为 null，status 携带样本状态字符串。 */
    public void publishMetrics(Long pointId, Map<String, Double> metrics, Double healthIndex, String alarmLevel,
            String modalityType, String channelCode, long timestamp, String status) {
        if (ModalityTypes.ROTATING_VIB.equals(modalityType)) return;
        submit(pointId, EVENT_METRICS,
                () -> buildMetricsFrame(timestamp, modalityType, channelCode, metrics, healthIndex, alarmLevel, status));
    }

    /** 快照帧推送（快照入库并完成标注后调用）；事件名按快照模态选择。 */
    public void publishSnapshot(Long pointId, ChannelSnapshotView snapshot) {
        submit(pointId, eventName(snapshot.modalityType()), () -> buildSnapshotFrame(snapshot));
    }

    /** 投递编排：活动事务 → afterCommit 后入队；否则直接入队。提交失败仅告警（推流永不影响主链路）。 */
    private void submit(Long pointId, String event, Supplier<Map<String, Object>> frame) {
        Runnable deliver = () -> deliver(pointId, event, frame);
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            publisher.execute(deliver);
                        } catch (RuntimeException exception) {
                            log.warn("推流任务提交失败 point={}: {}", pointId, exception.getMessage());
                        }
                    }
                });
                return;
            }
            publisher.execute(deliver);
        } catch (RuntimeException exception) {
            log.warn("推流任务提交失败 point={}: {}", pointId, exception.getMessage());
        }
    }

    /** 推流线程本体：无订阅者早退；序列化失败或发送异常仅摘除坏连接。 */
    private void deliver(Long pointId, String event, Supplier<Map<String, Object>> frame) {
        Set<SseEmitter> emitters = subscribers.get(pointId);
        if (emitters == null || emitters.isEmpty()) return;
        String payload;
        try {
            payload = mapper.writeValueAsString(frame.get());
        } catch (RuntimeException exception) {
            log.warn("推流帧序列化失败 point={}: {}", pointId, exception.getMessage());
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
            } catch (Exception exception) {
                log.warn("推流连接失效，摘除订阅 point={}: {}", pointId, exception.getMessage());
                unsubscribe(pointId, emitter);
                try {
                    emitter.completeWithError(exception);
                } catch (RuntimeException ignored) {
                    // 连接已断，完成回调静默
                }
            }
        }
    }

    /** 指标帧组装（snake_case 契约键）。包可见便于单测。 */
    Map<String, Object> buildMetricsFrame(long timestamp, String modalityType, String channelCode,
            Map<String, Double> metrics, Double healthIndex, String alarmLevel) {
        return buildMetricsFrame(timestamp, modalityType, channelCode, metrics, healthIndex, alarmLevel, null);
    }

    /** 指标帧组装（带可选 status：学习期/门禁帧携带样本状态字符串；status 为 null 时不输出该键）。 */
    Map<String, Object> buildMetricsFrame(long timestamp, String modalityType, String channelCode,
            Map<String, Double> metrics, Double healthIndex, String alarmLevel, String status) {
        Map<String, Object> frame = new java.util.LinkedHashMap<>();
        frame.put("frame_type", "METRICS_FRAME");
        frame.put("timestamp", timestamp);
        frame.put("modality_type", modalityType);
        frame.put("channel_code", channelCode);
        frame.put("metrics", metrics == null ? Map.of() : metrics);
        frame.put("health_index", healthIndex);
        frame.put("alarm_level", alarmLevel);
        if (status != null) frame.put("status", status);
        return frame;
    }

    /** 快照帧组装：指标字段 + curves/curve_units/annotations（annotations 缺失输出 {}，契约永不为 null）。 */
    Map<String, Object> buildSnapshotFrame(ChannelSnapshotView snapshot) {
        Map<String, Object> frame = new java.util.LinkedHashMap<>();
        frame.put("frame_type", frameType(snapshot.modalityType()));
        frame.put("timestamp", snapshot.timestamp());
        frame.put("modality_type", snapshot.modalityType());
        frame.put("channel_code", snapshot.channelCode());
        frame.put("metrics", snapshot.metrics());
        frame.put("health_index", snapshot.healthIndex());
        frame.put("alarm_level", null);
        frame.put("curves", snapshot.curves());
        frame.put("curve_units", snapshot.curveUnits());
        frame.put("annotations", snapshot.annotations() == null ? Map.of() : snapshot.annotations());
        return frame;
    }

    /** SSE 具名事件按模态分发：断路器行程 / 调节阀回环 / 其余统一指标帧。 */
    static String eventName(String modalityType) {
        return switch (modalityType == null ? "" : modalityType) {
            case ModalityTypes.SWITCHGEAR_EVENT -> EVENT_STROKE;
            case ModalityTypes.CONTROL_VALVE_PROCESS -> EVENT_VALVE;
            default -> EVENT_METRICS;
        };
    }

    static String frameType(String modalityType) {
        return switch (modalityType == null ? "" : modalityType) {
            case ModalityTypes.SWITCHGEAR_EVENT -> "STROKE_EVENT_FRAME";
            case ModalityTypes.CONTROL_VALVE_PROCESS -> "VALVE_LOOP_FRAME";
            default -> "METRICS_FRAME";
        };
    }

    @PreDestroy
    void shutdown() {
        if (publisher instanceof ExecutorService service) service.shutdownNow();
    }
}
