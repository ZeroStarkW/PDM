package com.pdm.mvp.telemetry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * PHM 协议 2.0.0 慢遥测载荷（文档 §2.1）：主题 devices/{asset_id}/{point_id}/telemetry。
 * asset_id 映射设备编码，测点由 设备+测点 全局定位（工厂经资产反查）；
 * metrics 按文档命名映射为平台特征码（feat_1x→freq_1x_amp、env_bpfo→envelope_bpfo_amp 等）。
 * data_quality / timestamp 为必填：REST 由 Bean Validation 拦截，MQTT 路径由 TelemetryService 给出 400，均不得拆箱为 NPE。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProtocolTelemetryRequest(
        @JsonProperty("protocol_version") String protocolVersion,
        @NotNull Long timestamp,
        @JsonProperty("asset_id") @NotBlank String assetId,
        @JsonProperty("point_id") @NotBlank String pointId,
        @JsonProperty("data_quality") @NotNull Integer dataQuality,
        @JsonProperty("operating_condition") ConditionInput operatingCondition,
        Metrics metrics) {

    public static final String SUPPORTED_PREFIX = "2.";

    public boolean isSupportedProtocol() {
        return protocolVersion != null && protocolVersion.startsWith(SUPPORTED_PREFIX);
    }

    /** 转换为统一契约包络；工厂编码经资产定位回填。 */
    public TelemetryEnvelope toEnvelope(String factoryId) {
        Map<String, Double> features = new LinkedHashMap<>();
        if (metrics != null) {
            put(features, VibrationContractAdapter.V_RMS, metrics.vRms());
            put(features, VibrationContractAdapter.A_PEAK, metrics.aPeak());
            put(features, VibrationContractAdapter.A_KURTOSIS, metrics.aKurtosis());
            put(features, VibrationContractAdapter.CREST_FACTOR, metrics.crestFactor());
            put(features, VibrationContractAdapter.FREQ_1X_AMP, metrics.feat1x());
            put(features, VibrationContractAdapter.FREQ_2X_AMP, metrics.feat2x());
            put(features, VibrationContractAdapter.FREQ_3X_AMP, metrics.feat3x());
            put(features, VibrationContractAdapter.FREQ_SUB_AMP, metrics.featSub());
            put(features, VibrationContractAdapter.ENVELOPE_RMS, metrics.envRms());
            put(features, VibrationContractAdapter.ENVELOPE_BPFO_AMP, metrics.envBpfo());
            put(features, VibrationContractAdapter.ENVELOPE_BPFI_AMP, metrics.envBpfi());
            put(features, VibrationContractAdapter.SEI_INDEX, metrics.seiIndex());
            put(features, VibrationContractAdapter.TEMPERATURE, metrics.tempBearing());
            put(features, VibrationContractAdapter.TEMP_AMBIENT, metrics.tempAmbient());
            if (metrics.tempBearing() != null && metrics.tempAmbient() != null)
                put(features, VibrationContractAdapter.TEMP_RISE, metrics.tempBearing() - metrics.tempAmbient());
        }
        OperatingCondition condition = operatingCondition == null ? null
                : new OperatingCondition(operatingCondition.rpm(), null, operatingCondition.steady());
        // 转速桥接为特征 rpm：指标未给出时取工况 rpm，使振动行保留 RPM（与协议 2.1.0 适配一致）
        if (condition != null && condition.rpm() != null) put(features, VibrationContractAdapter.RPM, condition.rpm());
        return new TelemetryEnvelope(com.pdm.mvp.modality.ModalityTypes.SUPPORTED_CONTRACT_VERSION,
                com.pdm.mvp.modality.ModalityTypes.ROTATING_VIB, timestamp, factoryId,
                assetId.trim().toUpperCase(Locale.ROOT), pointId.trim().toUpperCase(Locale.ROOT), dataQuality,
                condition, features, null, null);
    }

    private static void put(Map<String, Double> features, String code, Double value) {
        if (value != null) features.put(code, value);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConditionInput(Double rpm, @JsonProperty("is_steady") Boolean steady) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metrics(
            @JsonProperty("temp_bearing") Double tempBearing,
            @JsonProperty("temp_ambient") Double tempAmbient,
            @JsonProperty("v_rms") Double vRms,
            @JsonProperty("a_peak") Double aPeak,
            @JsonProperty("a_kurtosis") Double aKurtosis,
            @JsonProperty("crest_factor") Double crestFactor,
            @JsonProperty("feat_1x") Double feat1x,
            @JsonProperty("feat_2x") Double feat2x,
            @JsonProperty("feat_3x") Double feat3x,
            @JsonProperty("feat_sub") Double featSub,
            @JsonProperty("env_rms") Double envRms,
            @JsonProperty("env_bpfo") Double envBpfo,
            @JsonProperty("env_bpfi") Double envBpfi,
            @JsonProperty("sei_index") Double seiIndex) {}
}
