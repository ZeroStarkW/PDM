package com.pdm.mvp.telemetry;

import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.analytics.AnalyticsEngine;
import com.pdm.mvp.alert.AlertService;
import com.pdm.mvp.modality.ModalityPlugin;
import com.pdm.mvp.modality.ModalityPluginRegistry;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.timeseries.TelemetryStore;
import com.pdm.mvp.asset.PointRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 遥测接入编排：统一在契约层完成校验、测点模态匹配、去重、质量/工况门禁，
 * 再进入共享分析内核（尖峰抑制 → 基线学习 → 插件诊断与评分），最后写入告警链路。
 */
@Service
public class TelemetryService {
    private final AssetLookup assets;
    private final TelemetryValidator validator;
    private final TelemetryStore store;
    private final AnalyticsEngine analytics;
    private final PointRepository points;
    private final AlertService alerts;
    private final ModalityPluginRegistry plugins;

    /** v2 多模态推流枢纽：可选依赖（缺席或推流失败均不影响遥测主链路）。 */
    @Autowired(required = false)
    private com.pdm.mvp.snapshot.ModalityStreamHub streamHub;

    /** 波形帧缓存：可选依赖，用于同步旋转设备实时转速供示波器阶次跟踪。 */
    @Autowired(required = false)
    private com.pdm.mvp.waveform.WaveframeService waveframes;

    public TelemetryService(AssetLookup assets, TelemetryValidator validator, TelemetryStore store, AnalyticsEngine analytics,
            PointRepository points, AlertService alerts, ModalityPluginRegistry plugins) {
        this.assets = assets; this.validator = validator; this.store = store; this.analytics = analytics;
        this.points = points; this.alerts = alerts; this.plugins = plugins;
    }

    /** 旧版振动入口：保持旧 REST/MQTT 载荷与行为不变，经适配器转换为统一契约。 */
    @Transactional
    public TelemetryResult accept(TelemetryRequest request, String source) {
        validator.validate(request);
        return accept(VibrationContractAdapter.toEnvelope(request), source);
    }

    /** PHM 协议 2.0.0 入口（devices/{asset_id}/{point_id}/telemetry）：资产反查工厂后进入统一契约。
     *  MQTT 路径没有 Bean Validation：必填字段缺失在此给出 400，不得等到 toEnvelope 拆箱抛 NPE（毒消息循环）。 */
    @Transactional
    public TelemetryResult accept(ProtocolTelemetryRequest request, String source) {
        if (!request.isSupportedProtocol())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的协议版本: " + request.protocolVersion());
        if (request.timestamp() == null || request.dataQuality() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "timestamp 和 data_quality 为必填字段");
        if (request.assetId() == null || request.assetId().isBlank()
                || request.pointId() == null || request.pointId().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "asset_id 和 point_id 为必填字段");
        com.pdm.mvp.asset.PointEntity point = assets.requirePointByDeviceAndPoint(
                request.assetId().trim().toUpperCase(Locale.ROOT), request.pointId().trim().toUpperCase(Locale.ROOT));
        String factoryId = point.getDevice().getFactory().getFactoryCode();
        return accept(request.toEnvelope(factoryId), source);
    }

    /** PHM 协议 2.1.0 入口（phm/telemetry/{asset_id}/{channel_id}/telemetry 与 REST /api/v2/phm/telemetry 共用）。 */
    @Transactional
    public TelemetryResult accept(PhmTelemetryRequest request, String source) {
        if (!request.isSupportedProtocol())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的协议版本: " + request.protocolVersion());
        if (request.timestamp() == null || request.assetId() == null || request.assetId().isBlank()
                || request.channelId() == null || request.channelId().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "timestamp、asset_id 和 channel_id 为必填字段");
        com.pdm.mvp.asset.PointEntity point = assets.requirePointByDeviceAndPoint(
                request.assetId().trim().toUpperCase(Locale.ROOT), request.channelId().trim().toUpperCase(Locale.ROOT));
        String factoryId = point.getDevice().getFactory().getFactoryCode();
        return accept(request.toEnvelope(factoryId), source);
    }

    /** 统一契约入口：支持全部已注册模态（含 MCSA）。 */
    @Transactional
    public TelemetryResult accept(TelemetryEnvelope envelope, String source) {
        ModalityPlugin plugin = plugins.require(envelope.modalityType());
        validator.validate(envelope, plugin);
        PointEntity point = assets.requirePoint(envelope.factoryId(), envelope.deviceCode(), envelope.pointCode());
        if (!plugin.modalityType().equals(point.getModalityType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "测点模态与遥测模态不匹配");
        Instant sampledAt = envelope.timestampInstant();
        if (point.getLastReceivedAt() != null && !sampledAt.isAfter(point.getLastReceivedAt()))
            return new TelemetryResult(true, point.getId(), "DUPLICATE_OR_LATE_IGNORED", null,
                    source + " telemetry timestamp was already received or is out of order");
        point.recordIngest(sampledAt, Instant.now(), envelope.dataQuality());
        // 检修中（status=2）或已报废（status=3）设备静默抑制：不进入基线学习、劣化窗口与告警建单
        if (com.pdm.mvp.asset.DeviceEntity.isOutOfService(point.getDevice())) {
            analytics.reset(point.getId());
            alerts.resetDebounce(point.getId());
            clearLiveRpm(point.getId());
            String status = com.pdm.mvp.asset.DeviceEntity.outOfServiceStatus(point.getDevice());
            store.write(toPoint(envelope, null, status, 5, true));
            point.setLastHealthIndex(null);
            point.setLastStatus(status);
            point.setLastTelemetryAt(sampledAt);
            points.save(point);
            if (envelope.dataQuality() == 0) publishStreamFrame(point, envelope.features(), envelope.modalityType(),
                    sampledAt.toEpochMilli(), null, null, status);
            return new TelemetryResult(true, point.getId(), status, null,
                    source + " telemetry silenced because device status is " + status);
        }
        var gate = plugin.gate(envelope);
        if (envelope.dataQuality() != 0 || !gate.assessable()) {
            analytics.reset(point.getId());
            alerts.resetDebounce(point.getId());
            if (envelope.dataQuality() != 0 || gate.stopped()) clearLiveRpm(point.getId());
            boolean badQuality = envelope.dataQuality() != 0;
            String status = badQuality ? "SENSOR_CHECK" : gate.status();
            int statusCode = badQuality ? 4 : gate.statusCode();
            store.write(toPoint(envelope, null, status, statusCode, !badQuality && gate.stopped()));
            point.setLastHealthIndex(null);
            point.setLastStatus(status);
            point.setLastTelemetryAt(sampledAt);
            points.save(point);
            if (!badQuality) publishStreamFrame(point, envelope.features(), envelope.modalityType(),
                    sampledAt.toEpochMilli(), null, null, status);
            return new TelemetryResult(true, point.getId(), status, null, source + " telemetry accepted without fault scoring");
        }
        recordLiveRpm(point.getId(), envelope);
        var result = analytics.observe(point, envelope, plugin);
        if (result.isEmpty()) {
            store.write(toPoint(envelope, null, "ANALYSIS_BUFFERING", 0, false));
            String lastStatus = point.getLastStatus();
            if (point.getLastTelemetryAt() == null || "SENSOR_CHECK".equals(lastStatus)
                    || "STOPPED".equals(lastStatus) || "STOPPED_OR_SENSOR_CHECK".equals(lastStatus)
                    || "MAINTENANCE".equals(lastStatus) || "RETIRED".equals(lastStatus))
                point.setLastStatus("ANALYSIS_BUFFERING");
            points.save(point);
            publishStreamFrame(point, envelope.features(), envelope.modalityType(),
                    sampledAt.toEpochMilli(), null, null, "ANALYSIS_BUFFERING");
            return new TelemetryResult(true, point.getId(), "ANALYSIS_BUFFERING", null, source + " telemetry accepted");
        }
        var output = result.get();
        Double hi = output.assessment() == null ? null : output.assessment().healthIndex();
        store.write(toPoint(output.sample(), hi, output.status(), output.statusCode(), false));
        Instant analyzedAt = output.sample().timestampInstant();
        if (point.getLastTelemetryAt() == null || analyzedAt.isAfter(point.getLastTelemetryAt())) {
            point.setLastHealthIndex(hi);
            point.setLastStatus(output.status());
            point.setLastTelemetryAt(analyzedAt);
        }
        points.save(point);
        if (output.assessment() != null && plugin.alertsEnabled()) {
            var trigger = plugin.alertTrigger(point, output.sample(), output.baseline());
            alerts.observe(point.getId(), output.sample(), output.assessment(), trigger.value(), trigger.base(),
                    plugin.cadence() == ModalityPlugin.Cadence.EVENT);
        }
        if (output.assessment() == null) {
            // 学习期/尖峰抑制/发散帧：无评分，status 携带样本实际状态
            publishStreamFrame(point, output.sample().features(), output.sample().modalityType(),
                    analyzedAt.toEpochMilli(), null, null, output.status());
        } else {
            publishStreamFrame(point, output.sample().features(), output.sample().modalityType(),
                    analyzedAt.toEpochMilli(), hi, output.assessment().level(), null);
        }
        return new TelemetryResult(true, point.getId(), output.status(), hi, source + " telemetry accepted");
    }

    /**
     * v2 实时推流附加钩子：非振动模态每个被接受且质量正常的样本都推一帧（振动由 /ws/v1 推流，避免双推）。
     * 已评分帧携带 health_index + 分档；学习期/门禁/缓冲帧两值为 null、status 携带样本状态字符串；
     * 质量异常样本不推流（特征域不可信）。枢纽缺席或推流失败均不影响遥测主链路。
     */
    private void publishStreamFrame(PointEntity point, Map<String, Double> features, String modalityType,
            long timestamp, Double healthIndex, String alarmLevel, String status) {
        if (streamHub == null || ModalityTypes.ROTATING_VIB.equals(modalityType)) return;
        try {
            streamHub.publishMetrics(point.getId(), features, healthIndex, alarmLevel, modalityType,
                    point.getPointCode(), timestamp, status);
        } catch (RuntimeException ignored) {
            // 推流失败静默，不影响遥测主链路
        }
    }

    private void recordLiveRpm(Long pointId, TelemetryEnvelope envelope) {
        if (waveframes == null || pointId == null) return;
        Double rpm = envelope.featureOrNull(VibrationContractAdapter.RPM);
        if (rpm == null && envelope.condition() != null) rpm = envelope.condition().rpm();
        if (rpm != null && Double.isFinite(rpm) && rpm > 0) waveframes.recordRpm(pointId, rpm);
    }

    private void clearLiveRpm(Long pointId) {
        if (waveframes != null && pointId != null) waveframes.clearRpm(pointId);
    }

    private TelemetryPoint toPoint(TelemetryEnvelope envelope, Double hi, String status, int statusCode, boolean stopped) {
        boolean vibration = ModalityTypes.ROTATING_VIB.equals(envelope.modalityType());
        Map<String, Double> features = vibration ? vibrationFields(envelope) : envelope.features();
        return new TelemetryPoint(envelope.timestampInstant(), envelope.factoryId(), envelope.deviceCode(),
                envelope.pointCode(), envelope.modalityType(),
                envelope.featureOrNull(VibrationContractAdapter.V_RMS),
                envelope.featureOrNull(VibrationContractAdapter.A_PEAK),
                envelope.featureOrNull(VibrationContractAdapter.A_KURTOSIS),
                envelope.featureOrNull(VibrationContractAdapter.FREQ_1X_AMP),
                envelope.featureOrNull(VibrationContractAdapter.FREQ_2X_AMP),
                envelope.featureOrNull(VibrationContractAdapter.ENVELOPE_RMS),
                envelope.featureOrNull(VibrationContractAdapter.ENVELOPE_BPFO_AMP),
                envelope.featureOrNull(VibrationContractAdapter.TEMPERATURE),
                envelope.featureOrNull(VibrationContractAdapter.RPM),
                hi, envelope.dataQuality(), stopped, statusCode, status, envelope.topPeaks(),
                features, envelope.rawSnapshotRef());
    }

    private static Map<String, Double> vibrationFields(TelemetryEnvelope envelope) {
        Map<String, Double> fields = new LinkedHashMap<>();
        for (String code : VibrationContractAdapter.KNOWN_CODES) {
            Double value = envelope.featureOrNull(code);
            if (value != null) fields.put(code, value);
        }
        return fields;
    }
}
