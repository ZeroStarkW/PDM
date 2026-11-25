package com.pdm.mvp.telemetry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.pdm.mvp.mcsa.McsaPlugin;
import com.pdm.mvp.modality.ModalityTypes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 版本化统一契约入口载荷：REST /api/v2/telemetry 与 MQTT pdm/v2 主题共用结构。
 * 未知模态、缺失必需特征与非有限数值由共享校验拒绝。
 * timestamp 与 data_quality 为必填：REST 由 Bean Validation 拦截，MQTT 路径由 MqttTelemetryConsumer 校验后丢弃，均不得拆箱为 NPE。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContractTelemetryRequest(
        @JsonProperty("contract_version") Integer contractVersion,
        @JsonProperty("modality_type") String modalityType,
        @NotNull Long timestamp,
        @JsonProperty("factory_id") @NotBlank String factoryId,
        @JsonProperty("device_code") @NotBlank String deviceCode,
        @JsonProperty("point_code") @NotBlank String pointCode,
        @JsonProperty("data_quality") @NotNull Integer dataQuality,
        @JsonProperty("operating_condition") OperatingConditionInput operatingCondition,
        Map<String, Double> features,
        @JsonProperty("top_peaks") List<TelemetryRequest.TopPeak> topPeaks,
        @JsonProperty("raw_snapshot_ref") String rawSnapshotRef) {

    public TelemetryEnvelope toEnvelope() {
        OperatingCondition condition = operatingCondition == null ? null : operatingCondition.toCondition();
        return new TelemetryEnvelope(contractVersion == null ? 0 : contractVersion, modalityType, timestamp,
                factoryId, deviceCode, pointCode, dataQuality, condition, withConditionLoad(condition), topPeaks,
                rawSnapshotRef);
    }

    /** MCSA 必填 load_pct 而插件只读工况负载：指标未给出时以 operating_condition.load_pct/load_ratio 补入特征（不改动入参映射）。 */
    private Map<String, Double> withConditionLoad(OperatingCondition condition) {
        if (features == null || !ModalityTypes.MCSA.equals(ModalityTypes.normalize(modalityType))
                || features.containsKey(McsaPlugin.LOAD_PCT) || condition == null || condition.loadPct() == null) {
            return features;
        }
        Map<String, Double> bridged = new LinkedHashMap<>(features);
        bridged.put(McsaPlugin.LOAD_PCT, condition.loadPct());
        return bridged;
    }

    /**
     * 工况输入：规范键 rpm/load_pct/steady_state 与文档别名键 speed_rpm/load_ratio/is_steady 等价解析，
     * 原始键全量带入 OperatingCondition.raw 供机理插件按文档键名读取。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OperatingConditionInput(Double rpm, @JsonProperty("load_pct") Double loadPct,
            @JsonProperty("steady_state") Boolean steadyState,
            @JsonProperty("speed_rpm") Double speedRpm,
            @JsonProperty("load_ratio") Double loadRatio,
            @JsonProperty("is_steady") Boolean isSteady) {

        OperatingCondition toCondition() {
            Double rpmValue = rpm != null ? rpm : speedRpm;
            Double loadPctValue = loadPct != null ? loadPct : loadRatio == null ? null : loadRatio * 100.0;
            Boolean steadyValue = steadyState != null ? steadyState : isSteady;
            Map<String, Object> raw = new java.util.LinkedHashMap<>();
            if (rpm != null) raw.put("rpm", rpm);
            if (loadPct != null) raw.put("load_pct", loadPct);
            if (steadyState != null) raw.put("steady_state", steadyState);
            if (speedRpm != null) raw.put("speed_rpm", speedRpm);
            if (loadRatio != null) raw.put("load_ratio", loadRatio);
            if (isSteady != null) raw.put("is_steady", isSteady);
            return new OperatingCondition(rpmValue, loadPctValue, steadyValue, raw);
        }
    }
}
