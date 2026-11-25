package com.pdm.mvp.snapshot;

import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.snapshot.ChannelSnapshotViews.ChannelSnapshotView;
import com.pdm.mvp.waveform.WaveformSnapshotRepository;
import com.pdm.mvp.waveform.WaveformStorage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 多模态快速事件通道快照服务（协议 2.1.0 §2.2）：MQTT phm/snapshot 与 REST /api/v2/snapshots 两条入库路径、
 * 最新快照查询（事件模态查快照行，其余模态降级取振动波形快照）、行程/回环曲线标注计算与 v2 推流。
 */
@Service
public class ChannelSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(ChannelSnapshotService.class);
    /** MQTT 快照主题前缀（与 MqttTelemetryConsumer.SNAPSHOT_PREFIX 一致）。 */
    public static final String TOPIC_PREFIX = "phm/snapshot/";
    /** REST 直推时钟偏差上限：±1 天（REST 是联调兜底，载荷来自本机时间）。 */
    static final Duration REST_MAX_CLOCK_SKEW = Duration.ofDays(1);
    /** MQTT 时钟偏差上界：> 1 年拒绝毒消息，但容忍边缘断网补传（选择：容忍到 1 年，兼顾补传与垃圾数据）。 */
    static final Duration MQTT_MAX_CLOCK_SKEW = Duration.ofDays(365);

    private final ChannelSnapshotRepository snapshots;
    private final WaveformSnapshotRepository vibrationSnapshots;
    private final WaveformStorage storage;
    private final AssetLookup assets;
    private final com.pdm.mvp.alert.AlertRepository alerts;
    private final ModalityStreamHub streamHub;
    private final JsonMapper mapper;
    private final int maxPreviewPoints;

    public ChannelSnapshotService(ChannelSnapshotRepository snapshots, WaveformSnapshotRepository vibrationSnapshots,
            WaveformStorage storage, AssetLookup assets, com.pdm.mvp.alert.AlertRepository alerts,
            ModalityStreamHub streamHub, JsonMapper mapper,
            @Value("${pdm.waveform.max-preview-points:16384}") int maxPreviewPoints) {
        this.snapshots = snapshots;
        this.vibrationSnapshots = vibrationSnapshots;
        this.storage = storage;
        this.assets = assets;
        this.alerts = alerts;
        this.streamHub = streamHub;
        this.mapper = mapper;
        this.maxPreviewPoints = Math.max(64, maxPreviewPoints);
    }

    /** MQTT 入口：主题与载荷身份一致性校验后入库（事务边界在此）。时钟只设 1 年毒消息上界（容忍断网补传）。 */
    @Transactional
    public void ingest(String topic, String json) {
        PhmSnapshotPayload payload;
        try {
            payload = mapper.readValue(json, PhmSnapshotPayload.class);
        } catch (RuntimeException exception) {
            throw bad("快照载荷解析失败: " + exception.getMessage());
        }
        if (payload == null) throw bad("快照载荷为空");
        verifyTopic(topic, payload);
        if (payload.timestamp() != null && Math.abs(Duration.between(Instant.now(),
                Instant.ofEpochMilli(payload.timestamp())).toMillis()) > MQTT_MAX_CLOCK_SKEW.toMillis())
            throw bad("快照时间戳与服务器时间相差超过 1 年");
        accept(payload, false);
    }

    /** REST 直推入口（边缘无 MQTT 或联调兜底），返回 201 视图；时钟偏差要求 ±1 天内。 */
    @Transactional
    public ChannelSnapshotView accept(PhmSnapshotPayload payload) {
        return accept(payload, true);
    }

    private ChannelSnapshotView accept(PhmSnapshotPayload payload, boolean restStrict) {
        if (!payload.isSupportedProtocol())
            throw bad("不支持的协议版本: " + payload.protocolVersion());
        if (payload.timestamp() == null || payload.assetId() == null || payload.assetId().isBlank()
                || payload.channelId() == null || payload.channelId().isBlank()
                || payload.triggerType() == null || payload.triggerType().isBlank())
            throw bad("timestamp、asset_id、channel_id 和 trigger_type 为必填字段");
        if (!payload.isKnownTrigger())
            throw bad("trigger_type 只能是 TRIP_ACTION_BURST/THRESHOLD_EXCEEDED/SCHEDULED/ON_DEMAND");
        if (payload.healthIndex() != null && (!Double.isFinite(payload.healthIndex())
                || payload.healthIndex() < 0 || payload.healthIndex() > 100))
            throw bad("health_index 必须为 0-100 的有限数值");
        Instant sampledAt = Instant.ofEpochMilli(payload.timestamp());
        if (restStrict && Math.abs(Duration.between(Instant.now(), sampledAt).toMillis())
                > REST_MAX_CLOCK_SKEW.toMillis())
            throw bad("快照时间戳与服务器时间相差超过 1 天");
        List<Double> preview;
        Map<String, List<Double>> curves;
        try {
            preview = payload.checkedPreview(maxPreviewPoints);
            curves = payload.checkedCurves();
        } catch (IllegalArgumentException exception) {
            throw bad(exception.getMessage());
        }
        PointEntity point = assets.requirePointByDeviceAndPoint(
                payload.assetId().trim().toUpperCase(), payload.channelId().trim().toUpperCase());
        String modality = ModalityTypes.normalize(payload.modalityType());
        if (modality != null && !modality.equals(point.getModalityType()))
            throw bad("测点模态与快照模态不匹配");
        // 振动/ MCSA 快照走波形通道（devices/.../raw_waveform），快速事件快照表只收事件/过程模态
        if (ModalityTypes.ROTATING_VIB.equals(point.getModalityType()) || ModalityTypes.MCSA.equals(point.getModalityType()))
            throw bad("该模态使用振动波形通道");
        // 幂等重放：同测点同 sampled_at 的快照已入库时直接返回既有视图（MQTT QoS1 重投不产生重复行/重复推流）
        Optional<ChannelSnapshotEntity> replay =
                snapshots.findByPoint_IdAndSampledAtOrderByIdDesc(point.getId(), sampledAt);
        if (replay.isPresent()) return ChannelSnapshotViews.viewOf(replay.get(), point);
        double samplingRate = resolveSamplingRate(payload.samplingRate(), point);

        ChannelSnapshotEntity snapshot = new ChannelSnapshotEntity(point, payload.triggerType(),
                sampledAt, samplingRate,
                payload.pointsCount() == null ? preview.size() : payload.pointsCount(),
                preview, payload.healthIndex());
        snapshot.setStorageType("LOCAL_VOLUME");
        snapshot.setStorageRef(storage.store(point.getId(), payload.timestamp(), preview));
        snapshot.setSnapshotUrl(payload.edgeSnapshotUrl());
        snapshot.setCurves(curves);
        snapshot.setCurveUnits(payload.curveUnits());
        snapshot.setMetrics(payload.finiteMetrics());
        snapshot.setAnnotations(analyzeAnnotations(point.getModalityType(), curves, samplingRate));
        try {
            snapshot = snapshots.save(snapshot);
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            // V16 唯一约束兜底：并发同刻快照按幂等重复拒绝（事务回滚，客户端重试只会拿到已有行）
            throw new ResponseStatusException(HttpStatus.CONFLICT, "相同时间戳的通道快照已存在");
        }
        linkLatestAlert(point, snapshot, payload.triggerType());
        ChannelSnapshotView view = ChannelSnapshotViews.viewOf(snapshot, point);
        publishQuietly(point.getId(), view);
        log.info("通道快照入库 point={} channel={} trigger={} points={} rate={}", point.getId(),
                point.getPointCode(), payload.triggerType(), preview.size(), samplingRate);
        return view;
    }

    /**
     * 最新通道快照：SWITCHGEAR_EVENT / CONTROL_VALVE_PROCESS 取快照表最新行（标注缺失但有曲线时惰性补算，
     * 已存过空标注 {} 的不再反复重算）；其余模态（ROTATING_VIB / MCSA / 未知）降级适配最新振动波形快照；
     * 均无数据时 404（前端空态）。
     */
    @Transactional
    public ChannelSnapshotView latest(String code) {
        PointEntity point = assets.resolvePoint(code);
        String modality = point.getModalityType();
        if (ModalityTypes.SWITCHGEAR_EVENT.equals(modality) || ModalityTypes.CONTROL_VALVE_PROCESS.equals(modality)) {
            ChannelSnapshotEntity snapshot = snapshots.findTopByPoint_IdOrderBySampledAtDesc(point.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "该测点还没有通道快照"));
            if (snapshot.getAnnotations() == null
                    && snapshot.getCurves() != null && !snapshot.getCurves().isEmpty()) {
                snapshot.setAnnotations(analyzeAnnotations(modality, snapshot.getCurves(), snapshot.getSamplingRate()));
                snapshot = snapshots.save(snapshot);
            }
            return ChannelSnapshotViews.viewOf(snapshot, point);
        }
        return vibrationFallback(point);
    }

    private ChannelSnapshotView vibrationFallback(PointEntity point) {
        com.pdm.mvp.waveform.WaveformSnapshotEntity snapshot =
                vibrationSnapshots.findTopByPoint_IdOrderBySampledAtDesc(point.getId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "该测点还没有波形快照"));
        return ChannelSnapshotViews.vibrationOf(snapshot, point);
    }

    /** 模态标注计算：断路器行程标注 / 调节阀回环分析；无曲线或其余模态返回 null（不落 JSON）。 */
    static Map<String, Object> analyzeAnnotations(String modalityType, Map<String, List<Double>> curves,
            Double samplingRate) {
        if (curves == null || curves.isEmpty()) return null;
        if (ModalityTypes.SWITCHGEAR_EVENT.equals(modalityType))
            return StrokeAnnotationCalculator.fromCurves(curves, samplingRate).toMap();
        if (ModalityTypes.CONTROL_VALVE_PROCESS.equals(modalityType))
            return ValveLoopAnalyzer.fromCurves(curves).toMap();
        return null;
    }

    /** 采样率：载荷优先（须为正），缺省取测点 sampling_rate，再缺省 0。 */
    private static double resolveSamplingRate(Double payloadRate, PointEntity point) {
        if (payloadRate != null && payloadRate > 0) return payloadRate;
        Integer pointRate = point.getSamplingRate();
        return pointRate != null && pointRate > 0 ? pointRate : 0.0;
    }

    private void verifyTopic(String topic, PhmSnapshotPayload payload) {
        if (payload.assetId() == null || payload.channelId() == null)
            throw bad("asset_id 和 channel_id 为必填字段");
        String expected = TOPIC_PREFIX + payload.assetId().trim().toUpperCase() + "/"
                + payload.channelId().trim().toUpperCase();
        if (!expected.equalsIgnoreCase(topic)) throw bad("主题与载荷身份不一致");
    }

    /** 告警关联（与波形路径同构）：动作突发/越限触发的快照回填最新未闭环告警的 storage_ref，已有链接不覆盖。 */
    private void linkLatestAlert(PointEntity point, ChannelSnapshotEntity snapshot, String triggerType) {
        if (!ChannelSnapshotEntity.TRIGGER_TRIP_ACTION_BURST.equals(triggerType)
                && !ChannelSnapshotEntity.TRIGGER_THRESHOLD.equals(triggerType)) return;
        com.pdm.mvp.alert.AlertEntity alert =
                alerts.findTopByPoint_IdAndStatusLessThanOrderByCreatedAtDesc(point.getId(), (short) 2);
        if (alert != null && alert.getRawSnapshotUrl() == null) {
            alert.setRawSnapshotUrl(snapshot.getStorageRef());
        }
    }

    /** v2 推流枢纽推送：快照已落库并完成标注后调用；失败仅告警，不影响入库主链路。 */
    private void publishQuietly(Long pointId, ChannelSnapshotView view) {
        try {
            streamHub.publishSnapshot(pointId, view);
        } catch (RuntimeException exception) {
            log.warn("快照推流失败 point={}: {}", pointId, exception.getMessage());
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
