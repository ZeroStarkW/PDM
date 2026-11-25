package com.pdm.mvp.telemetry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 内部统一遥测契约：模态类型 + 工况 + 特征编码到有限数值的映射。
 * 现有 REST/MQTT 振动入口经 {@link VibrationContractAdapter} 转换为本契约；
 * 新版本化入口（REST /api/v2/telemetry、MQTT pdm/v2 主题）直接接收本契约。
 * {@code rawSnapshotRef} 仅为可选元数据，平台不读取对象存储。
 */
public record TelemetryEnvelope(
        int contractVersion,
        String modalityType,
        long timestamp,
        String factoryId,
        String deviceCode,
        String pointCode,
        int dataQuality,
        OperatingCondition condition,
        Map<String, Double> features,
        List<TelemetryRequest.TopPeak> topPeaks,
        String rawSnapshotRef) {

    public Instant timestampInstant() { return Instant.ofEpochMilli(timestamp); }

    /** 读取特征值，缺失返回 null（供可选特征）。 */
    public Double featureOrNull(String code) { return features == null ? null : features.get(code); }

    /** 读取必填特征值，缺失抛出异常（契约校验应已拦截）。 */
    public double feature(String code) {
        Double value = featureOrNull(code);
        if (value == null) throw new IllegalArgumentException("缺少必需特征: " + code);
        return value;
    }

    /** 特征存在且为有限数值。 */
    public boolean hasFeature(String code) {
        Double value = featureOrNull(code);
        return value != null && Double.isFinite(value);
    }
}
