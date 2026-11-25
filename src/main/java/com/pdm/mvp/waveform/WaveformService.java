package com.pdm.mvp.waveform;

import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.alert.AlertEntity;
import com.pdm.mvp.alert.AlertRepository;
import com.pdm.mvp.telemetry.MqttCommandPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
 * 波形快照服务：MQTT devices/.../raw_waveform 与 REST 两条入库路径、
 * 专家召唤（MQTT 下行指令）、最新快照与谱帧查询（文档 §2.2/§7.1）。
 */
@Service
public class WaveformService {
    private static final Logger log = LoggerFactory.getLogger(WaveformService.class);
    private final WaveformSnapshotRepository snapshots;
    private final WaveformStorage storage;
    private final WaveframeService frames;
    private final AssetLookup assets;
    private final AlertRepository alerts;
    private final com.pdm.mvp.asset.PointRepository points;
    private final MqttCommandPublisher mqtt;
    private final JsonMapper mapper;
    private final Clock clock;
    private final int maxPreviewPoints;
    private final com.pdm.mvp.analytics.SpectrumBaselineService spectrumBaseline;

    public WaveformService(WaveformSnapshotRepository snapshots, WaveformStorage storage, WaveframeService frames,
            AssetLookup assets, AlertRepository alerts, com.pdm.mvp.asset.PointRepository points,
            MqttCommandPublisher mqtt, JsonMapper mapper,
            com.pdm.mvp.analytics.SpectrumBaselineService spectrumBaseline,
            @Value("${pdm.waveform.max-preview-points:16384}") int maxPreviewPoints) {
        this.snapshots = snapshots; this.storage = storage; this.frames = frames; this.assets = assets;
        this.alerts = alerts; this.points = points; this.mqtt = mqtt; this.mapper = mapper;
        this.spectrumBaseline = spectrumBaseline;
        this.clock = Clock.systemUTC();
        this.maxPreviewPoints = Math.max(64, maxPreviewPoints);
    }

    /** MQTT 入口：完成主题与载荷一致性校验后入库（事务边界在此，内部 accept 不再自调用代理）。 */
    @Transactional
    public void ingest(String topic, String json) {
        RawWaveformPayload payload;
        try {
            payload = mapper.readValue(json, RawWaveformPayload.class);
        } catch (RuntimeException exception) {
            throw bad("波形载荷解析失败: " + exception.getMessage());
        }
        if (payload == null) throw bad("波形载荷为空");
        String expected = "devices/" + payload.assetId().trim().toUpperCase() + "/"
                + payload.pointId().trim().toUpperCase() + "/raw_waveform";
        if (!expected.equals(topic)) throw bad("主题与载荷身份不一致");
        accept(payload);
    }

    /** REST 直推入口（边缘无 MQTT 或联调兜底）。 */
    @Transactional
    public WaveformView accept(RawWaveformPayload payload) {
        if (!payload.isSupportedProtocol())
            throw bad("不支持的协议版本: " + payload.protocolVersion());
        if (!payload.isKnownTrigger()) throw bad("trigger_type 只能是 THRESHOLD_EXCEEDED/SCHEDULED/ON_DEMAND");
        if (payload.samplingRate() == null || payload.samplingRate() <= 0) throw bad("sampling_rate 必须为正数");
        List<Double> preview = payload.previewWaveform();
        if (preview == null || preview.isEmpty()) throw bad("preview_waveform 不能为空");
        if (preview.size() > maxPreviewPoints)
            throw bad("preview_waveform 超过平台上限 " + maxPreviewPoints + " 点");
        PointEntity point = assets.requirePointByDeviceAndPoint(
                payload.assetId().trim().toUpperCase(), payload.pointId().trim().toUpperCase());
        Instant sampledAt = Instant.ofEpochMilli(payload.timestamp());
        // MQTT QoS1 重投幂等：同 (point_id, sampled_at) 已有快照则复用返回，不重复落盘、入库或计入频谱基线样本
        Optional<WaveformSnapshotEntity> existing = snapshots.findByPoint_IdAndSampledAt(point.getId(), sampledAt);
        if (existing.isPresent()) {
            WaveformSnapshotEntity replay = existing.get();
            WaveframeService.Waveframe cachedFrame = frames.frame(point.getId());
            if (cachedFrame == null || cachedFrame.timestamp() != sampledAt.toEpochMilli()) cachedFrame = frames.analyze(replay);
            log.info("波形快照重投去重 point={} sampledAt={}", point.getPointCode(), sampledAt);
            return WaveformView.from(replay, cachedFrame);
        }
        WaveformSnapshotEntity snapshot = new WaveformSnapshotEntity(point, payload.triggerType(), sampledAt,
                payload.samplingRate(), preview.size(), payload.unit(), preview, point.getLastHealthIndex());
        snapshot.setStorageType(payload.storageType() == null || payload.storageType().isBlank()
                ? "LOCAL_VOLUME" : payload.storageType());
        snapshot.setStorageRef(storage.store(point.getId(), payload.timestamp(), preview));
        snapshot = snapshots.save(snapshot);
        WaveframeService.Waveframe frame = frames.analyze(snapshot);
        // 频谱基线带学习：仅非越限触发帧（SCHEDULED/ON_DEMAND）进入学习，防止故障越限波形冻结为健康参考带
        if (!WaveformSnapshotEntity.TRIGGER_THRESHOLD.equals(payload.triggerType())) {
            try {
                spectrumBaseline.observe(point, frame);
            } catch (RuntimeException exception) {
                log.warn("频谱基线带学习失败 point={}: {}", point.getPointCode(), exception.getMessage());
            }
        }
        if (WaveformSnapshotEntity.TRIGGER_THRESHOLD.equals(payload.triggerType())) linkLatestAlert(point, snapshot);
        log.info("波形快照入库 point={} trigger={} points={} rate={}", point.getPointCode(),
                payload.triggerType(), preview.size(), payload.samplingRate());
        return WaveformView.from(snapshot, frames.frame(point.getId()));
    }

    /** 最新波形快照与谱帧；启动后首查时重建缓存。 */
    @Transactional(readOnly = true)
    public WaveformView latest(Long pointId) {
        WaveformSnapshotEntity snapshot = snapshots.findTopByPoint_IdOrderBySampledAtDesc(pointId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "该测点还没有波形快照"));
        WaveframeService.Waveframe frame = frames.frame(pointId);
        if (frame == null || frame.timestamp() != snapshot.getSampledAt().toEpochMilli()) frame = frames.analyze(snapshot);
        return WaveformView.from(snapshot, frame);
    }

    /** 专家召唤：向边缘发布 ON_DEMAND 抓取指令（MQTT 未启用时返回 false）。 */
    public boolean requestCapture(Long pointId) {
        // 本方法无事务上下文，而 device 为 LAZY：findById 返回后会话即关闭，组装主题时须连同设备一并取回
        PointEntity point = points.findByIdWithDeviceAndBearing(pointId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "测点不存在"));
        String topic = "devices/" + point.getDevice().getDeviceCode() + "/" + point.getPointCode() + "/cmd/capture_waveform";
        String payload = mapper.writeValueAsString(java.util.Map.of(
                "protocol_version", "2.0.0",
                "command", "capture_waveform",
                "trigger_type", WaveformSnapshotEntity.TRIGGER_ON_DEMAND,
                "timestamp", clock.millis()));
        return mqtt.publish(topic, payload);
    }

    public boolean captureEnabled() { return mqtt.enabled(); }

    private void linkLatestAlert(PointEntity point, WaveformSnapshotEntity snapshot) {
        AlertEntity alert = alerts.findTopByPoint_IdAndStatusLessThanOrderByCreatedAtDesc(point.getId(), (short) 2);
        if (alert != null && alert.getRawSnapshotUrl() == null) {
            alert.setRawSnapshotUrl(snapshot.getStorageRef());
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /** 波形快照视图：元数据 + 时域波形 + FFT 谱 + 包络谱。 */
    public record WaveformView(Long id, Long pointId, String triggerType, Instant sampledAt, double samplingRate,
            int pointsCount, String unit, String storageType, String storageRef, Double healthIndex,
            double[] waveform, double[] fftFreqs, double[] fftAmps, double[] envelopeFreqs, double[] envelopeAmps) {
        static WaveformView from(WaveformSnapshotEntity snapshot, WaveframeService.Waveframe frame) {
            return new WaveformView(snapshot.getId(), snapshot.getPoint().getId(), snapshot.getTriggerType(),
                    snapshot.getSampledAt(), snapshot.getSamplingRate(), snapshot.getPointsCount(), snapshot.getUnit(),
                    snapshot.getStorageType(), snapshot.getStorageRef(), snapshot.getHealthIndex(),
                    frame == null ? new double[0] : frame.waveform(),
                    frame == null ? new double[0] : frame.fftFreqs(),
                    frame == null ? new double[0] : frame.fftAmps(),
                    frame == null ? new double[0] : frame.envelopeFreqs(),
                    frame == null ? new double[0] : frame.envelopeAmps());
        }
    }
}
