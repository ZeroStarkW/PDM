package com.pdm.mvp.switchgear;

import com.pdm.mvp.analytics.HealthAssessment;
import com.pdm.mvp.analytics.HealthScale;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.modality.BaselineSnapshot;
import com.pdm.mvp.modality.MechanismPluginStrategy;
import com.pdm.mvp.modality.MechanismStrategyPlugin;
import com.pdm.mvp.modality.MechanismPluginStrategy.PluginDiagnosisResult;
import com.pdm.mvp.modality.ModalityPlugin.BaselineLearning;
import com.pdm.mvp.modality.ModalityPlugin.Cadence;
import com.pdm.mvp.modality.ModalityPlugin.MetadataField;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 高压断路器机械特性插件（设计说明书 §4.3 插件 2）：SWITCHGEAR_EVENT 事件型动作触发模态。
 * 机理特征：分闸时间 / 线圈电流峰值 / 合闸速度 / 触头磨损率（UHF 局放计数仅存储，不学习不评分）。
 * 门禁：动作型事件均为有效诊断点；评分：分闸时间分级倍率（硬限击穿）+ 线圈电流下限 + 合闸速度（越低越差）。
 */
@Component("switchgearEventPlugin")
public class CircuitBreakerPlugin extends MechanismStrategyPlugin {

    public static final String TRIP_DURATION_MS = "trip_duration_ms";
    public static final String PEAK_COIL_CURRENT_A = "peak_coil_current_a";
    public static final String CLOSING_VELOCITY_MPS = "closing_velocity_mps";
    public static final String CONTACT_WEAR_RATIO = "contact_wear_ratio";
    /** 可选特征：局放脉冲计数（典型中位数为 0，纳入学习会使 CV 门槛发散，仅存储展示）。 */
    public static final String UHF_PD_COUNT = "uhf_pd_count";

    public static final String DURATION_ATTENTION_RATIO_KEY = "duration_attention_ratio";
    public static final String DURATION_DANGER_RATIO_KEY = "duration_danger_ratio";
    public static final String CURRENT_ATTENTION_RATIO_KEY = "current_attention_ratio";

    private final SwitchgearProperties properties;

    public CircuitBreakerPlugin(SwitchgearProperties properties) {
        super(Duration.ofHours(24));
        this.properties = properties;
    }

    @Override public String modalityType() { return ModalityTypes.SWITCHGEAR_EVENT; }

    @Override public String identifier() { return "switchgearEventPlugin"; }

    @Override public Cadence cadence() { return Cadence.EVENT; }

    @Override public BaselineLearning baselineLearning() { return BaselineLearning.pooled(properties.sampleRetention()); }

    @Override public Duration degradationWindow(Duration defaultWindow) { return properties.degradationWindow(); }

    @Override public Duration eventStaleAfter() { return properties.staleAfter(); }

    /** 断网补传的动作事件允许滞后到 max-past-delay（默认 30 天）；超出仍按时钟偏差拒绝。 */
    @Override public Duration maxPastDelay() { return properties.maxPastDelay(); }

    @Override public boolean alertsEnabled() { return properties.alertsEnabled(); }

    @Override public Set<String> requiredFeatures() {
        return new LinkedHashSet<>(List.of(TRIP_DURATION_MS, PEAK_COIL_CURRENT_A));
    }

    /** 参与基线学习与劣化跟踪的机理特征（文档指标名，保持声明顺序）。 */
    @Override public Set<String> mechanismFeatureCodes() {
        return new LinkedHashSet<>(List.of(TRIP_DURATION_MS, PEAK_COIL_CURRENT_A,
                CLOSING_VELOCITY_MPS, CONTACT_WEAR_RATIO));
    }

    /** 收敛特征：触头磨损率近似单调累积、CV 无收敛意义，不参与锁定门槛与 dk/dt 拟合（统计仍照常学习存储）。 */
    @Override public Set<String> convergenceFeatureCodes() {
        return new LinkedHashSet<>(List.of(TRIP_DURATION_MS, PEAK_COIL_CURRENT_A, CLOSING_VELOCITY_MPS));
    }

    @Override public Set<String> lowerIsWorseFeatures() {
        return Set.of(PEAK_COIL_CURRENT_A, CLOSING_VELOCITY_MPS);
    }

    @Override public String primaryFeatureCode() { return TRIP_DURATION_MS; }

    /** 时序查询特征列：机理特征在前，仅存储的局放计数（uhf_pd_count）随后。 */
    @Override public Set<String> seriesFeatureCodes() {
        Set<String> codes = new LinkedHashSet<>(super.seriesFeatureCodes());
        codes.add(UHF_PD_COUNT);
        return codes;
    }

    @Override public void validateFeatures(TelemetryEnvelope envelope) {
        double trip = envelope.feature(TRIP_DURATION_MS);
        if (trip <= 0 || trip > 5000.0) throw bad("trip_duration_ms 超出物理合理范围（0-5000ms）");
        if (envelope.feature(PEAK_COIL_CURRENT_A) < 0) throw bad("peak_coil_current_a 必须为非负数值");
        Double velocity = envelope.featureOrNull(CLOSING_VELOCITY_MPS);
        if (velocity != null && velocity < 0) throw bad("closing_velocity_mps 必须为非负数值");
        Double wear = envelope.featureOrNull(CONTACT_WEAR_RATIO);
        if (wear != null && (wear < 0 || wear > 1.0)) throw bad("contact_wear_ratio 超出物理合理范围（0-1）");
        Double pd = envelope.featureOrNull(UHF_PD_COUNT);
        if (pd != null && pd < 0) throw bad("uhf_pd_count 必须为非负数值");
    }

    @Override public boolean isOperatingSteady(Map<String, Object> opCondition) {
        return true; // 动作型事件均为有效诊断点
    }

    @Override public PluginDiagnosisResult processDiagnosis(Map<String, Double> current, Map<String, Double> base,
            Map<String, Object> metadataConfig) {
        double attentionRatio = MechanismPluginStrategy.configValue(
                metadataConfig, DURATION_ATTENTION_RATIO_KEY, properties.durationAttentionRatio());
        double dangerRatio = MechanismPluginStrategy.configValue(
                metadataConfig, DURATION_DANGER_RATIO_KEY, properties.durationDangerRatio());
        double currentLimitRatio = MechanismPluginStrategy.configValue(
                metadataConfig, CURRENT_ATTENTION_RATIO_KEY, properties.currentAttentionRatio());

        double trip = current.getOrDefault(TRIP_DURATION_MS, 40.0);
        double baseTrip = base.getOrDefault(TRIP_DURATION_MS, 40.0);
        double peak = current.getOrDefault(PEAK_COIL_CURRENT_A, 5.0);
        double basePeak = base.getOrDefault(PEAK_COIL_CURRENT_A, 5.0);

        // 分闸时间滞后物理判据（绝对容差：超基线 10% 关注，超 20% 危险且视为物理限值击穿）
        double durationRatio = trip / Math.max(1.0, baseTrip);
        double scoreDuration = 100.0;
        if (durationRatio > dangerRatio) scoreDuration = 25.0;
        else if (durationRatio > attentionRatio) scoreDuration = 60.0;

        // 线圈电流峰值不足判据（铁芯卡涩或控制电压跌落；越低越差）
        double currentRatio = peak / Math.max(0.1, basePeak);
        double scoreCurrent = 100.0;
        if (currentRatio < currentLimitRatio) scoreCurrent = 40.0;

        // 合闸速度下降判据（越低越差），可选特征缺失时满分
        Double velocity = current.get(CLOSING_VELOCITY_MPS);
        Double baseVelocity = base.get(CLOSING_VELOCITY_MPS);
        boolean velocityScored = velocity != null && baseVelocity != null && baseVelocity > 1e-4;
        double scoreVelocity = velocityScored ? HealthScale.relativeScore(velocity, baseVelocity, true) : 100.0;

        double staticScore = Math.min(scoreDuration, Math.min(scoreCurrent, scoreVelocity));
        List<HealthAssessment.FeatureContribution> contributions = List.of(
                new HealthAssessment.FeatureContribution("分闸时间", trip, baseTrip, durationRatio, scoreDuration),
                new HealthAssessment.FeatureContribution("线圈电流峰值", peak, basePeak, currentRatio, scoreCurrent),
                new HealthAssessment.FeatureContribution("合闸速度", velocity, baseVelocity,
                        velocityScored ? velocity / baseVelocity : null, scoreVelocity));

        boolean hardLimitBreached = durationRatio > dangerRatio;
        String cause;
        if (staticScore < 70.0 && scoreDuration <= Math.min(scoreCurrent, scoreVelocity)) {
            cause = String.format(Locale.ROOT, "分闸动作迟缓 (实际耗时 %.1fms, 超出基线 %.1f%%), 机械传动卡涩",
                    trip, (durationRatio - 1.0) * 100.0);
        } else if (staticScore < 70.0 && scoreCurrent <= scoreVelocity) {
            cause = String.format(Locale.ROOT, "脱扣线圈驱动力不足 (电流峰值仅 %.2fA), 检查合分闸回路供电", peak);
        } else if (staticScore < 70.0) {
            cause = String.format(Locale.ROOT, "触头动作速度下降 (合闸速度 %.2fm/s), 检查机构弹簧储能与润滑状态",
                    velocity == null ? 0.0 : velocity);
        } else {
            cause = "开关机构特性良好";
        }
        return new PluginDiagnosisResult(staticScore, cause, hardLimitBreached, contributions);
    }

    /** 通道机理参数约束（与全局配置同一套）：按实际生效值校验 danger > attention > 1、0 < 电流比率 < 1 且全部有限。 */
    @Override public void validateMetadata(Map<String, Object> metadata) {
        double attention = MechanismPluginStrategy.metadataValue(
                metadata, DURATION_ATTENTION_RATIO_KEY, properties.durationAttentionRatio());
        double danger = MechanismPluginStrategy.metadataValue(
                metadata, DURATION_DANGER_RATIO_KEY, properties.durationDangerRatio());
        double current = MechanismPluginStrategy.metadataValue(
                metadata, CURRENT_ATTENTION_RATIO_KEY, properties.currentAttentionRatio());
        if (!Double.isFinite(attention) || !Double.isFinite(danger) || danger <= attention || attention <= 1.0)
            throw bad("metadata_config 分闸时间倍率无效: 须满足 duration_danger_ratio > duration_attention_ratio > 1"
                    + "（未填写的键沿用全局配置）");
        if (!Double.isFinite(current) || current <= 0 || current >= 1.0)
            throw bad("metadata_config 电流比率无效: current_attention_ratio 必须在 (0,1) 区间");
    }

    @Override public List<MetadataField> metadataFields() {
        return List.of(
                new MetadataField(DURATION_ATTENTION_RATIO_KEY, "分闸时间关注倍率",
                        "分闸耗时超出基线该倍率进入关注档", properties.durationAttentionRatio()),
                new MetadataField(DURATION_DANGER_RATIO_KEY, "分闸时间危险倍率",
                        "分闸耗时超出基线该倍率判危险并视为物理限值击穿", properties.durationDangerRatio()),
                new MetadataField(CURRENT_ATTENTION_RATIO_KEY, "线圈电流下限比率",
                        "线圈电流峰值低于基线该比率判驱动力不足", properties.currentAttentionRatio()));
    }

    @Override protected String adviceFor(PluginDiagnosisResult result) {
        return result.isHardLimitBreached()
                ? "立即禁止该间隔合分闸操作，安排机械特性带电检测与传动部件检修。"
                : "复测一次分闸线圈电流与行程曲线，对比基线确认传动部件状态。";
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
