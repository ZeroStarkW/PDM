package com.pdm.mvp.modality;

import com.pdm.mvp.analytics.HealthAssessment;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.diagnostics.DiagnosticMatch;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模态机理插件：质量校验、去重、工况分发、尖峰抑制、基线学习、评分分档与告警防抖
 * 留在共享内核；特征定义、工况门禁与物理解释由本接口的模态实现提供。
 * 每个 modalityType 只允许注册一个实现（由 {@link ModalityPluginRegistry} 启动校验）。
 */
public interface ModalityPlugin {
    /** 模态标识，与测点 modality_type 一致。 */
    String modalityType();

    /** 进入评分必填的特征编码（契约校验缺一拒绝）。 */
    Set<String> requiredFeatures();

    /** 必填与有限性之外的特征域校验（物理合理范围），违规抛 400。 */
    default void validateFeatures(TelemetryEnvelope envelope) {}

    /**
     * 工况门禁：返回可评分、停机或工况不可评估（如负载波动/非稳态）。
     */
    ConditionGate gate(TelemetryEnvelope envelope);

    /** 参与尖峰抑制检测的特征编码。 */
    Set<String> spikeFeatureCodes();

    /** 读取尖峰抑制用的特征值：默认读原始特征，若为派生特征（如 MCSA sideband_ratio）回落基线特征映射。 */
    default Double spikeFeatureValue(TelemetryEnvelope envelope, String code) {
        Double direct = envelope.featureOrNull(code);
        return direct != null ? direct : baselineFeatureValues(envelope).get(code);
    }

    /** 参与基线学习的特征编码（含派生特征）。 */
    Set<String> baselineFeatureCodes();

    /** 评分就绪所需的基线特征编码；任一缺失时保持 BASELINE_LEARNING，不评分。 */
    Set<String> scoringBaselineFeatures();

    /** 送入基线学习的特征值（可包含派生特征）。 */
    Map<String, Double> baselineFeatureValues(TelemetryEnvelope envelope);

    /** 故障归因；无确定性结论时返回通用匹配，不做断言。 */
    DiagnosticMatch diagnose(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline);

    /** 健康评分（相对基线 + 绝对标准），输出统一分档。 */
    HealthAssessment score(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline,
            DiagnosticMatch diagnosis);

    /** 告警触发幅值（当前值与基线值），写入告警触发/基线列。 */
    AlertTrigger alertTrigger(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline);

    /** 告警触发幅值（triggerAmplitude/baselineAmplitude）对应的特征编码；未声明返回 null。 */
    default String triggerFeatureCode() { return null; }

    /** 趋势图默认特征编码；未声明返回 null。 */
    default String trendFeatureCode() { return null; }

    /** 告警阈值是否已启用（如 MCSA 需离线比对与现场试点验收后开启）。 */
    boolean alertsEnabled();

    /** 基线锁定后随样本更新动态劣化跟踪（默认无操作）。 */
    default void observeDegradation(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline) {}

    /** 数据质量/工况门禁失败时清空插件运行期状态（默认无操作）。 */
    default void resetRuntimeState(Long pointId) {}

    /** 采样节律：连续型周期上报 / 事件型动作触发（稀疏）。内核据此调整尖峰抑制、防抖窗口、基线学习与数据新鲜度。 */
    enum Cadence { CONTINUOUS, EVENT }

    default Cadence cadence() { return Cadence.CONTINUOUS; }

    /** 数据新鲜度时限；事件型模态应覆盖为更长的时限。 */
    default Duration staleAfter(Duration defaultStaleAfter) { return defaultStaleAfter; }

    /**
     * 允许的遥测时间戳滞后（相对服务器时间的过去方向）：默认 0 表示只容忍统一的 5 分钟时钟偏差；
     * 事件型模态可放宽，以接收断网补传（store-and-forward）的动作事件。未来方向的偏差始终只容忍 5 分钟。
     */
    default Duration maxPastDelay() { return Duration.ZERO; }

    /**
     * 多工况分箱：样本应归入的基线工况箱（{@code bin_index}）。
     * 默认恒为 0（单工况基线，存量模态行为零变化）；变频旋转振动与可配负载分箱的模态覆盖委托
     * {@code OperatingRegimeBinner}，实现按转速段 / 负载段独立学习与对口评分。
     */
    default short resolveBinIndex(PointEntity point, TelemetryEnvelope sample) { return 0; }

    /** 基线学习策略（按模态声明）。 */
    record BaselineLearning(Duration window, boolean pooled) {
        /** window == null → 使用全局 pdm.baseline.learning-duration；要求样本覆盖窗口。 */
        public static BaselineLearning windowed(Duration window) { return new BaselineLearning(window, false); }
        /** 事件型：仅要求样本数达到门槛，不要求覆盖时间窗口；window 为样本最长保留期。 */
        public static BaselineLearning pooled(Duration window) { return new BaselineLearning(window, true); }
    }

    default BaselineLearning baselineLearning() { return BaselineLearning.windowed(null); }

    /** 时序查询输出的特征列，默认 = 必需特征 ∪ 基线特征（保持插入顺序）。 */
    default Set<String> seriesFeatureCodes() {
        Set<String> codes = new LinkedHashSet<>(requiredFeatures());
        codes.addAll(baselineFeatureCodes());
        return codes;
    }

    /** 通道可配置的机理参数键（写入测点 metadata_config，由插件 processDiagnosis 读取）。 */
    default List<MetadataField> metadataFields() { return List.of(); }

    record MetadataField(String key, String label, String description, Double defaultValue) {}

    /** 通道机理参数（测点 metadata_config）约束校验，与全局配置同一套约束；违规抛 400（默认不校验）。 */
    default void validateMetadata(Map<String, Object> metadata) {}

    /** 插件标识（Spring Bean 名称语义，对应文档 plugin_identifier）。 */
    default String identifier() { return getClass().getSimpleName(); }

    /** 工况门禁结论：可评分 / 停机 / 不可评估。 */
    record ConditionGate(boolean assessable, boolean stopped, String status, int statusCode, String message) {
        public static ConditionGate accept() { return new ConditionGate(true, false, null, 0, null); }
        public static ConditionGate stoppedGate() {
            return new ConditionGate(false, true, "STOPPED", 5, "设备停机");
        }
        public static ConditionGate notAssessable(String message) {
            return new ConditionGate(false, false, ModalityTypes.CONDITION_REJECTED,
                    ModalityTypes.CONDITION_REJECTED_CODE, message);
        }
    }

    /** 告警触发幅值对。 */
    record AlertTrigger(double value, double base) {}
}
