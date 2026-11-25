package com.pdm.mvp.telemetry;

import com.pdm.mvp.modality.ModalityTypes;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 旧版振动遥测载荷 ↔ 统一契约（ROTATING_VIB）双向适配。
 * 旧 REST/MQTT 入口与现有模拟器无需任何修改，仍进入旋转振动插件并保持现有行为。
 */
public final class VibrationContractAdapter {
    public static final String V_RMS = "v_rms";
    public static final String A_PEAK = "a_peak";
    public static final String A_KURTOSIS = "a_kurtosis";
    public static final String FREQ_1X_AMP = "freq_1x_amp";
    public static final String FREQ_2X_AMP = "freq_2x_amp";
    public static final String FREQ_3X_AMP = "feat_3x_amp";
    public static final String FREQ_SUB_AMP = "feat_sub_amp";
    public static final String ENVELOPE_RMS = "envelope_rms";
    public static final String ENVELOPE_BPFO_AMP = "envelope_bpfo_amp";
    public static final String ENVELOPE_BPFI_AMP = "env_bpfi_amp";
    public static final String SEI_INDEX = "sei_index";
    public static final String CREST_FACTOR = "crest_factor";
    public static final String RPM = "rpm";
    public static final String TEMPERATURE = "temperature";
    public static final String TEMP_AMBIENT = "temp_ambient";
    public static final String TEMP_RISE = "temp_rise";

    /** 协议 2.0.0 扩展特征码（可选）：包络 BPFI、3X/0.44X 阶次、温升、crest、沉积能量指数。 */
    public static final java.util.List<String> EXTENDED_CODES = java.util.List.of(
            FREQ_3X_AMP, FREQ_SUB_AMP, ENVELOPE_BPFI_AMP, SEI_INDEX, CREST_FACTOR, TEMP_RISE);

    /** 旧振动载荷全部特征码（含可选），保持存储字段顺序稳定。 */
    public static final java.util.List<String> KNOWN_CODES = java.util.List.of(V_RMS, A_PEAK, A_KURTOSIS,
            FREQ_1X_AMP, FREQ_2X_AMP, ENVELOPE_RMS, ENVELOPE_BPFO_AMP, RPM, TEMPERATURE,
            FREQ_3X_AMP, FREQ_SUB_AMP, ENVELOPE_BPFI_AMP, SEI_INDEX, CREST_FACTOR, TEMP_AMBIENT, TEMP_RISE);

    private VibrationContractAdapter() {}

    /** 旧载荷 → 契约：特征码沿用旧 JSON 字段名，工况仅带转速。 */
    public static TelemetryEnvelope toEnvelope(TelemetryRequest request) {
        var values = request.telemetry();
        Map<String, Double> features = new LinkedHashMap<>();
        features.put(V_RMS, values.vRms());
        features.put(A_PEAK, values.aPeak());
        features.put(A_KURTOSIS, values.aKurtosis());
        features.put(FREQ_1X_AMP, values.freq1xAmp());
        features.put(FREQ_2X_AMP, values.freq2xAmp());
        putIfNotNull(features, ENVELOPE_RMS, values.envelopeRms());
        putIfNotNull(features, ENVELOPE_BPFO_AMP, values.envelopeBpfoAmp());
        putIfNotNull(features, RPM, values.rpm());
        putIfNotNull(features, TEMPERATURE, values.temperature());
        return new TelemetryEnvelope(ModalityTypes.SUPPORTED_CONTRACT_VERSION, ModalityTypes.ROTATING_VIB,
                request.timestamp(), request.factoryId(), request.deviceCode(), request.pointCode(),
                request.dataQuality(), new OperatingCondition(values.rpm(), null, null),
                features, request.topPeaks(), null);
    }

    /** 契约 → 旧载荷：供振动插件复用既有诊断与评分实现（特征必填项已由契约校验保证）。 */
    public static TelemetryRequest toRequest(TelemetryEnvelope envelope) {
        var features = envelope.features();
        var values = new TelemetryRequest.TelemetryValues(
                features.get(V_RMS), features.get(A_PEAK), features.get(A_KURTOSIS),
                features.get(FREQ_1X_AMP), features.get(FREQ_2X_AMP),
                features.get(ENVELOPE_RMS), features.get(ENVELOPE_BPFO_AMP),
                features.get(RPM), features.get(TEMPERATURE), null);
        return new TelemetryRequest(envelope.timestamp(), envelope.factoryId(), envelope.deviceCode(),
                envelope.pointCode(), envelope.dataQuality(), values, envelope.topPeaks());
    }

    private static void putIfNotNull(Map<String, Double> features, String code, Double value) {
        if (value != null) features.put(code, value);
    }
}
