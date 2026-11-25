package com.pdm.mvp.snapshot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PHM 协议 2.1.0 快速事件快照载荷（文档 §2.2）：主题 phm/snapshot/{asset_id}/{channel_id}，
 * 与 REST POST /api/v2/snapshots 共用。asset_id 映射设备编码，channel_id 映射测点编码；
 * curves 键为曲线编码（time_axis_ms / coil_current_a / travel_stroke_mm / cv_pct / fb_pct 等），
 * snapshot_url 为边缘侧对象存储引用（平台仍按本地卷归档 preview_curve）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PhmSnapshotPayload(
        @JsonProperty("protocol_version") String protocolVersion,
        Long timestamp,
        @JsonProperty("asset_id") String assetId,
        @JsonProperty("channel_id") String channelId,
        @JsonProperty("modality_type") String modalityType,
        @JsonProperty("trigger_type") String triggerType,
        @JsonProperty("sampling_rate") Double samplingRate,
        @JsonProperty("points_count") Integer pointsCount,
        @JsonProperty("storage_backend") String storageBackend,
        @JsonProperty("snapshot_url") String snapshotUrl,
        @JsonProperty("preview_curve") List<Double> previewCurve,
        Map<String, List<Double>> curves,
        @JsonProperty("curve_units") Map<String, String> curveUnits,
        Map<String, Double> metrics,
        @JsonProperty("health_index") Double healthIndex,
        @JsonProperty("data_quality") Integer dataQuality) {

    public static final String SUPPORTED_PREFIX = "2.1";
    /** 曲线映射上限：最多 8 条曲线。 */
    public static final int MAX_CURVES = 8;
    /** 每条曲线的值数上限。 */
    public static final int MAX_CURVE_POINTS = 16384;

    public boolean isSupportedProtocol() {
        return protocolVersion != null && protocolVersion.startsWith(SUPPORTED_PREFIX);
    }

    /** 触发类型白名单：TRIP_ACTION_BURST / THRESHOLD_EXCEEDED / SCHEDULED / ON_DEMAND。 */
    public boolean isKnownTrigger() {
        return ChannelSnapshotEntity.TRIGGER_TRIP_ACTION_BURST.equals(triggerType)
                || ChannelSnapshotEntity.TRIGGER_THRESHOLD.equals(triggerType)
                || ChannelSnapshotEntity.TRIGGER_SCHEDULED.equals(triggerType)
                || ChannelSnapshotEntity.TRIGGER_ON_DEMAND.equals(triggerType);
    }

    /** 校验预览曲线：非空、不超平台上限、全部为有限数值（违规抛 IllegalArgumentException，服务层转 400）。 */
    public List<Double> checkedPreview(int maxPoints) {
        if (previewCurve == null || previewCurve.isEmpty()) throw new IllegalArgumentException("preview_curve 不能为空");
        if (previewCurve.size() > maxPoints)
            throw new IllegalArgumentException("preview_curve 超过平台上限 " + maxPoints + " 点");
        for (Double value : previewCurve)
            if (value == null || !Double.isFinite(value)) throw new IllegalArgumentException("preview_curve 含非法数值");
        return previewCurve;
    }

    /** 校验曲线映射：最多 8 条 × 每条 16384 点且全部数值有限（违规抛 IllegalArgumentException，消息点名 curves 字段）；空映射返回 null。 */
    public Map<String, List<Double>> checkedCurves() {
        if (curves == null || curves.isEmpty()) return null;
        if (curves.size() > MAX_CURVES)
            throw new IllegalArgumentException("curves 数量超过上限 " + MAX_CURVES + " 条");
        Map<String, List<Double>> checked = new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> entry : curves.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) continue;
            if (entry.getValue().size() > MAX_CURVE_POINTS)
                throw new IllegalArgumentException("curves." + entry.getKey()
                        + " 超过每条上限 " + MAX_CURVE_POINTS + " 点");
            for (Double value : entry.getValue())
                if (value == null || !Double.isFinite(value))
                    throw new IllegalArgumentException("curves." + entry.getKey() + " 含非法数值");
            checked.put(entry.getKey(), entry.getValue());
        }
        return checked.isEmpty() ? null : checked;
    }

    /** 机理指标：仅保留有限数值（字符串/布尔/非有限值静默丢弃，与 2.1.0 遥测语义一致）。 */
    public Map<String, Double> finiteMetrics() {
        if (metrics == null) return null;
        Map<String, Double> finite = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : metrics.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) continue;
            Double value = entry.getValue();
            if (value == null || !Double.isFinite(value)) continue;
            finite.put(entry.getKey(), value);
        }
        return finite.isEmpty() ? null : finite;
    }

    /** 边缘对象存储引用：空白视为缺失。 */
    public String edgeSnapshotUrl() {
        return snapshotUrl == null || snapshotUrl.isBlank() ? null : snapshotUrl;
    }
}
