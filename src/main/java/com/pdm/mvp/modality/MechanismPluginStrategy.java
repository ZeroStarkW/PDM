package com.pdm.mvp.modality;

import com.pdm.mvp.analytics.HealthAssessment;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 机理策略插件 SPI（设计说明书 §4.2）：在 {@link ModalityPlugin} 之上补充文档命名契约 ——
 * 工况稳态门禁视图、机理特征归一化诊断与确定性故障归因，供 {@link DiagnosticMicrokernel} 统一调度。
 */
public interface MechanismPluginStrategy extends ModalityPlugin {

    /** 该插件支持的数据模态标识（文档命名）。 */
    default String getModalityType() { return modalityType(); }

    /** 工况门禁校验：判定设备当前是否处于有效稳态。入参为合并后的工况视图（原始键 + 规范化键 rpm/load_pct/steady_state）。 */
    boolean isOperatingSteady(Map<String, Object> opCondition);

    /** 机理特征归一化评估与确定性故障归因推断。必须是纯函数（内核会调用多次）。 */
    PluginDiagnosisResult processDiagnosis(Map<String, Double> currentMetrics, Map<String, Double> baselineMetrics,
            Map<String, Object> metadataConfig);

    /** 参与基线学习与劣化跟踪的机理特征编码，默认取全部必需特征。 */
    default Set<String> mechanismFeatureCodes() { return requiredFeatures(); }

    /**
     * 参与基线锁定收敛判据（最大 CV 门槛）与劣化速率拟合的机理特征编码，默认 = 全部机理特征。
     * 仅按绝对阈值评分或天然无收敛性的特征（如触头磨损率、超声泄漏 RMS）应从此集合排除，避免通道永久 DIVERGED。
     */
    default Set<String> convergenceFeatureCodes() { return mechanismFeatureCodes(); }

    /** “越低越差”的特征编码；未列出的特征按“越高越差”处理。 */
    default Set<String> lowerIsWorseFeatures() { return Set.of(); }

    /** 劣化速率（dk/dt）拟合窗口。 */
    default Duration degradationWindow(Duration defaultWindow) { return defaultWindow; }

    /** 告警触发幅值所用的主特征编码（必需特征按字典序取首项，保证确定性）。 */
    default String primaryFeatureCode() {
        return requiredFeatures().stream().sorted().findFirst().orElse(null);
    }

    /** 告警触发幅值与趋势图默认特征均取主特征。 */
    @Override default String triggerFeatureCode() { return primaryFeatureCode(); }

    @Override default String trendFeatureCode() { return primaryFeatureCode(); }

    /** 事件型模态的数据新鲜度时限（动作间隔可达数天）。 */
    default Duration eventStaleAfter() { return Duration.ofDays(30); }

    @Override default Duration staleAfter(Duration defaultStaleAfter) {
        return cadence() == Cadence.EVENT ? eventStaleAfter() : defaultStaleAfter;
    }

    @Override default Set<String> spikeFeatureCodes() { return mechanismFeatureCodes(); }

    @Override default Set<String> baselineFeatureCodes() { return mechanismFeatureCodes(); }

    @Override default Set<String> scoringBaselineFeatures() { return requiredFeatures(); }

    @Override default Map<String, Double> baselineFeatureValues(TelemetryEnvelope envelope) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (String code : mechanismFeatureCodes()) {
            Double value = envelope.featureOrNull(code);
            if (value != null) values.put(code, value);
        }
        return values;
    }

    @Override default ConditionGate gate(TelemetryEnvelope envelope) {
        return isOperatingSteady(merged(envelope))
                ? ConditionGate.accept()
                : ConditionGate.notAssessable("非稳态工况，暂不可评估");
    }

    /** 合并工况视图：原始键在前（空值跳过，不得遮蔽规范化键），规范化键 rpm/load_pct/steady_state 在原始键缺失且分量非空时补入。 */
    static Map<String, Object> merged(TelemetryEnvelope envelope) {
        Map<String, Object> merged = new LinkedHashMap<>();
        var condition = envelope == null ? null : envelope.condition();
        if (condition == null) return merged;
        for (var entry : condition.raw().entrySet()) {
            if (entry.getValue() != null) merged.put(entry.getKey(), entry.getValue());
        }
        if (!merged.containsKey("rpm") && condition.rpm() != null) merged.put("rpm", condition.rpm());
        if (!merged.containsKey("load_pct") && condition.loadPct() != null) merged.put("load_pct", condition.loadPct());
        if (!merged.containsKey("steady_state") && condition.steadyState() != null)
            merged.put("steady_state", condition.steadyState());
        return merged;
    }

    /** 读取通道机理参数（metadata_config）数值：Number 优先，否则回落默认（通道级覆盖 > 全局配置）。 */
    static double configValue(Map<String, Object> metadataConfig, String key, double fallback) {
        if (metadataConfig == null) return fallback;
        Object value = metadataConfig.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    /**
     * 通道机理参数校验用取值：未给出时回落全局默认；给出则必须是有限数值，否则 400。
     * 取值语义与 {@link #configValue} 一致（约束按实际生效的参数校验，而非只校验被覆盖的那一个键）。
     */
    static double metadataValue(Map<String, Object> metadata, String key, double fallback) {
        Object value = metadata == null ? null : metadata.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number))
            throw badMetadata("metadata_config 参数必须为数值: " + key);
        if (!Double.isFinite(number.doubleValue()))
            throw badMetadata("metadata_config 参数必须为有限数值: " + key);
        return number.doubleValue();
    }

    static ResponseStatusException badMetadata(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    /** 机理诊断结论：静态健康分、确定性归因、绝对物理限值击穿标识与特征贡献明细。 */
    record PluginDiagnosisResult(double staticHealthScore, String defectAttribution, boolean isHardLimitBreached,
            List<HealthAssessment.FeatureContribution> contributions) {

        public PluginDiagnosisResult(double staticHealthScore, String defectAttribution, boolean isHardLimitBreached) {
            this(staticHealthScore, defectAttribution, isHardLimitBreached, List.of());
        }
    }
}
