package com.pdm.mvp.telemetry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.pdm.mvp.mcsa.McsaPlugin;
import com.pdm.mvp.modality.ModalityTypes;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * PHM 协议 2.1.0 慢周期特征遥测载荷（文档 §2.1）：主题 phm/telemetry/{asset_id}/{channel_id}。
 * asset_id 映射设备编码，channel_id 映射测点编码（工厂经资产反查）；
 * metrics 键即平台特征码，工况原始键全量保留供机理插件读取。
 * 文档命名指标按模态适配：旋转振动 feat_1x/env_bpfo/temp_bearing 等映射为平台特征码（与协议 2.0.0 一致），
 * MCSA 的 load_pct 缺省时取工况负载。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PhmTelemetryRequest(
        @JsonProperty("protocol_version") String protocolVersion,
        @NotNull Long timestamp,
        @JsonProperty("asset_id") @NotBlank String assetId,
        @JsonProperty("channel_id") @NotBlank String channelId,
        @JsonProperty("modality_type") String modalityType,
        @JsonProperty("data_quality") Integer dataQuality,
        @JsonProperty("operating_condition") Map<String, Object> operatingCondition,
        Map<String, Object> metrics,
        @JsonProperty("raw_snapshot_ref") String rawSnapshotRef,
        @JsonProperty("snapshot_url") String snapshotUrl) {

    public static final String SUPPORTED_PREFIX = "2.1";

    /** 文档 §2.1 旋转振动指标名 → 平台特征码（与协议 2.0.0 {@link ProtocolTelemetryRequest.Metrics} 映射一致）。 */
    private static final Map<String, String> ROTATING_ALIASES = Map.ofEntries(
            Map.entry("feat_1x", VibrationContractAdapter.FREQ_1X_AMP),
            Map.entry("feat_2x", VibrationContractAdapter.FREQ_2X_AMP),
            Map.entry("feat_3x", VibrationContractAdapter.FREQ_3X_AMP),
            Map.entry("feat_sub", VibrationContractAdapter.FREQ_SUB_AMP),
            Map.entry("env_rms", VibrationContractAdapter.ENVELOPE_RMS),
            Map.entry("env_bpfo", VibrationContractAdapter.ENVELOPE_BPFO_AMP),
            Map.entry("env_bpfi", VibrationContractAdapter.ENVELOPE_BPFI_AMP),
            Map.entry("temp_bearing", VibrationContractAdapter.TEMPERATURE),
            Map.entry("temp_ambient", VibrationContractAdapter.TEMP_AMBIENT));

    public boolean isSupportedProtocol() {
        return protocolVersion != null && protocolVersion.startsWith(SUPPORTED_PREFIX);
    }

    /** 转换为统一契约包络；工厂编码经资产定位回填。 */
    public TelemetryEnvelope toEnvelope(String factoryId) {
        String modality = ModalityTypes.normalize(modalityType);
        OperatingCondition condition = condition();
        return new TelemetryEnvelope(ModalityTypes.SUPPORTED_CONTRACT_VERSION, modality, timestamp, factoryId,
                assetId.trim().toUpperCase(Locale.ROOT), channelId.trim().toUpperCase(Locale.ROOT),
                dataQuality == null ? 0 : dataQuality, condition, features(modality, condition), null, snapshotRef());
    }

    /** 特征集合：metrics 仅保留有限数值（字符串/布尔/空值静默丢弃），再按模态做文档命名 → 平台特征码适配。 */
    private Map<String, Double> features(String modality, OperatingCondition condition) {
        Map<String, Double> numeric = new LinkedHashMap<>();
        if (metrics == null) return numeric;
        for (var entry : metrics.entrySet()) {
            if (!(entry.getValue() instanceof Number number) || !Double.isFinite(number.doubleValue())) continue;
            if (entry.getKey() == null || entry.getKey().isBlank()) continue;
            numeric.put(entry.getKey(), number.doubleValue());
        }
        if (ModalityTypes.ROTATING_VIB.equals(modality)) return rotatingFeatures(numeric, condition);
        // MCSA 必填 load_pct 而插件只读工况负载：指标未给出时以 operating_condition.load_pct/load_ratio 补入
        if (ModalityTypes.MCSA.equals(modality) && !numeric.containsKey(McsaPlugin.LOAD_PCT)
                && condition != null && condition.loadPct() != null) {
            numeric.put(McsaPlugin.LOAD_PCT, condition.loadPct());
        }
        return numeric;
    }

    /**
     * 旋转振动：平台特征码原样保留，文档命名指标按别名映射（平台码已给出时别名不覆盖），仅保留已知振动码。
     * 温升由轴承−环境温度派生；指标未给转速时取工况 speed_rpm/rpm 桥接为特征 rpm，使振动行保留 RPM。
     */
    private static Map<String, Double> rotatingFeatures(Map<String, Double> numeric, OperatingCondition condition) {
        Map<String, Double> features = new LinkedHashMap<>();
        for (String code : VibrationContractAdapter.KNOWN_CODES) {
            Double value = numeric.get(code);
            if (value != null) features.put(code, value);
        }
        for (var entry : numeric.entrySet()) {
            String code = ROTATING_ALIASES.get(entry.getKey());
            if (code != null) features.putIfAbsent(code, entry.getValue());
        }
        Double bearing = features.get(VibrationContractAdapter.TEMPERATURE);
        Double ambient = features.get(VibrationContractAdapter.TEMP_AMBIENT);
        if (bearing != null && ambient != null) features.put(VibrationContractAdapter.TEMP_RISE, bearing - ambient);
        if (!features.containsKey(VibrationContractAdapter.RPM) && condition != null && condition.rpm() != null)
            features.put(VibrationContractAdapter.RPM, condition.rpm());
        return features;
    }

    /** 工况：分量按别名键推导（speed_rpm/rpm、load_pct/load_ratio、is_steady/steady_state），raw 全量保留。 */
    private OperatingCondition condition() {
        if (operatingCondition == null) return null;
        return new OperatingCondition(number("speed_rpm", "rpm"),
                loadPct(), bool("is_steady", "steady_state"), operatingCondition);
    }

    private Double loadPct() {
        Object pct = operatingCondition.get("load_pct");
        if (pct instanceof Number number) return number.doubleValue();
        Object ratio = operatingCondition.get("load_ratio");
        return ratio instanceof Number number ? number.doubleValue() * 100.0 : null;
    }

    private Double number(String... keys) {
        for (String key : keys) {
            if (operatingCondition.get(key) instanceof Number number) return number.doubleValue();
        }
        return null;
    }

    private Boolean bool(String... keys) {
        for (String key : keys) {
            if (operatingCondition.get(key) instanceof Boolean flag) return flag;
        }
        return null;
    }

    private String snapshotRef() {
        return rawSnapshotRef != null && !rawSnapshotRef.isBlank() ? rawSnapshotRef
                : snapshotUrl != null && !snapshotUrl.isBlank() ? snapshotUrl : null;
    }
}
