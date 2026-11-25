package com.pdm.mvp.snapshot;

import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.waveform.WaveformSnapshotEntity;
import java.util.List;
import java.util.Map;

/**
 * 快照/回环前端契约视图：字段名与前端工作站契约完全一致（camelCase 原样序列化）。
 */
public final class ChannelSnapshotViews {

    private ChannelSnapshotViews() {}

    /**
     * 通道快照视图（GET /api/v2/snapshots/{code}/latest 与 POST /api/v2/snapshots 返回体）。
     * timestamp 为毫秒 epoch，sampledAt 为 ISO-8601 字符串；空映射序列化为 {}。
     */
    public record ChannelSnapshotView(Long id, Long pointId, String channelCode, String deviceCode,
            String modalityType, String triggerType, long timestamp, String sampledAt,
            Double samplingRate, Integer pointsCount, String engineeringUnit,
            String storageBackend, String snapshotUrl, String storageRef, Double healthIndex,
            List<Double> previewCurve, Map<String, List<Double>> curves, Map<String, String> curveUnits,
            Map<String, Double> metrics, Map<String, Object> annotations) {
    }

    /** 持久化快照行 → 视图（curves/curveUnits/metrics/annotations 缺失时输出空映射，契约键永不为 JSON null）。 */
    public static ChannelSnapshotView viewOf(ChannelSnapshotEntity snapshot, PointEntity point) {
        return new ChannelSnapshotView(snapshot.getId(), point.getId(), point.getPointCode(),
                point.getDevice().getDeviceCode(), point.getModalityType(), snapshot.getTriggerType(),
                snapshot.getSampledAt().toEpochMilli(), snapshot.getSampledAt().toString(),
                snapshot.getSamplingRate(), snapshot.getPointsCount(), point.getEngineeringUnit(),
                snapshot.getStorageType(), snapshot.getSnapshotUrl(), snapshot.getStorageRef(),
                snapshot.getHealthIndex(),
                snapshot.getPreview(),
                snapshot.getCurves() == null ? Map.of() : snapshot.getCurves(),
                snapshot.getCurveUnits() == null ? Map.of() : snapshot.getCurveUnits(),
                snapshot.getMetrics() == null ? Map.of() : snapshot.getMetrics(),
                snapshot.getAnnotations() == null ? Map.of() : snapshot.getAnnotations());
    }

    /**
     * 振动/其余模态回退视图：适配最新振动波形快照（文档 §6.1 快照接口对非事件模态的降级）。
     * previewCurve 取波形 preview，curves/metrics/annotations 为空映射，单位取波形 unit。
     */
    public static ChannelSnapshotView vibrationOf(WaveformSnapshotEntity snapshot, PointEntity point) {
        return new ChannelSnapshotView(snapshot.getId(), point.getId(), point.getPointCode(),
                point.getDevice().getDeviceCode(), point.getModalityType(), snapshot.getTriggerType(),
                snapshot.getSampledAt().toEpochMilli(), snapshot.getSampledAt().toString(),
                snapshot.getSamplingRate(), snapshot.getPointsCount(), snapshot.getUnit(),
                snapshot.getStorageType(), snapshot.getStorageRef(), snapshot.getStorageRef(),
                snapshot.getHealthIndex(),
                snapshot.getPreview(),
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * 回环分析响应（GET /api/v2/channels/{code}/hysteresis）。
     * status ∈ OK / NO_DATA / UNSUPPORTED_MODALITY；from/to 为样本实际覆盖范围（首/末配对样本毫秒 epoch，空为 null），
     * 不再回显请求窗口；truncated 表示原始行数触及服务端上限（limit，仅返回最新样本）。
     * window 为 ISO-8601 时长（默认 PT1H，非法 400，超 P7D 按 P7D 截断）。
     */
    public record ValveLoop(String channelCode, String modalityType, String window, int sampleCount,
            Long from, Long to, String status, ValveLoopAnalyzer.ValveLoopAnalysis analysis,
            List<LoopPoint> points, boolean truncated, int limit) {
    }

    /** 回环采样点（timestamp 毫秒 epoch）。 */
    public record LoopPoint(long timestamp, double cv, double fb) {}
}
