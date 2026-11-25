package com.pdm.mvp.controlvalve;

import com.pdm.mvp.analytics.HealthAssessment;
import com.pdm.mvp.analytics.HealthScale;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.modality.BaselineSnapshot;
import com.pdm.mvp.modality.MechanismPluginStrategy;
import com.pdm.mvp.modality.MechanismStrategyPlugin;
import com.pdm.mvp.modality.MechanismPluginStrategy.PluginDiagnosisResult;
import com.pdm.mvp.modality.ModalityPlugin.BaselineLearning;
import com.pdm.mvp.modality.ModalityPlugin.MetadataField;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 工艺控制调节阀插件（设计说明书 §4.3 插件 3）：CONTROL_VALVE_PROCESS 连续低频过程量模态。
 * 机理特征：回环死区 / 行程滞后 / 超声泄漏 / 填料摩擦力；cv_pct/fb_pct 仅存储与查询（前端迟滞回环展示）。
 * 门禁：仅稳态样本可评估；评分：死区分级恶化倍率 + 超声泄漏绝对阈值（击穿即硬限）。
 */
@Component("controlValvePlugin")
public class ControlValvePlugin extends MechanismStrategyPlugin {

    public static final String HYSTERESIS_DEADBAND_PCT = "hysteresis_deadband_pct";
    public static final String TRAVEL_TIME_LAG_MS = "travel_time_lag_ms";
    public static final String ULTRASONIC_LEAK_RMS = "ultrasonic_leak_rms";
    public static final String FRICTION_FORCE_KN = "friction_force_kn";
    /** 回环特征：开度指令/反馈，仅存储与查询，不参与机理评分。 */
    public static final String CV_PCT = "cv_pct";
    public static final String FB_PCT = "fb_pct";

    public static final String DEADBAND_ATTENTION_RATIO_KEY = "deadband_attention_ratio";
    public static final String DEADBAND_DANGER_RATIO_KEY = "deadband_danger_ratio";
    public static final String LEAK_ATTENTION_RMS_KEY = "leak_attention_rms";
    public static final String LEAK_DANGER_RMS_KEY = "leak_danger_rms";

    private final ControlValveProperties properties;

    /** degradationWindow：注入全局 pdm.health.degradation-window 作为 dk/dt 拟合窗口（此前被硬编码为 24 小时）。 */
    public ControlValvePlugin(ControlValveProperties properties,
            @Value("${pdm.health.degradation-window:PT24H}") Duration degradationWindow) {
        super(degradationWindow);
        this.properties = properties;
    }

    @Override public String modalityType() { return ModalityTypes.CONTROL_VALVE_PROCESS; }

    @Override public String identifier() { return "controlValvePlugin"; }

    @Override public BaselineLearning baselineLearning() { return BaselineLearning.windowed(properties.learningWindow()); }

    @Override public boolean alertsEnabled() { return properties.alertsEnabled(); }

    @Override public Set<String> requiredFeatures() {
        return new LinkedHashSet<>(List.of(HYSTERESIS_DEADBAND_PCT, ULTRASONIC_LEAK_RMS));
    }

    @Override public Set<String> mechanismFeatureCodes() {
        return new LinkedHashSet<>(List.of(HYSTERESIS_DEADBAND_PCT, TRAVEL_TIME_LAG_MS,
                ULTRASONIC_LEAK_RMS, FRICTION_FORCE_KN));
    }

    /** 收敛特征：超声泄漏只按绝对阈值评分且噪声大，不参与锁定门槛与 dk/dt 拟合（统计仍照常学习存储）。 */
    @Override public Set<String> convergenceFeatureCodes() {
        return new LinkedHashSet<>(List.of(HYSTERESIS_DEADBAND_PCT, TRAVEL_TIME_LAG_MS, FRICTION_FORCE_KN));
    }

    @Override public String primaryFeatureCode() { return HYSTERESIS_DEADBAND_PCT; }

    /** 时序查询特征列：机理特征在前，回环特征 cv_pct/fb_pct 仅追加用于展示。 */
    @Override public Set<String> seriesFeatureCodes() {
        return new LinkedHashSet<>(List.of(HYSTERESIS_DEADBAND_PCT, TRAVEL_TIME_LAG_MS, ULTRASONIC_LEAK_RMS,
                FRICTION_FORCE_KN, CV_PCT, FB_PCT));
    }

    @Override public void validateFeatures(TelemetryEnvelope envelope) {
        for (String code : List.of(HYSTERESIS_DEADBAND_PCT, TRAVEL_TIME_LAG_MS, ULTRASONIC_LEAK_RMS, FRICTION_FORCE_KN)) {
            Double value = envelope.featureOrNull(code);
            if (value != null && value < 0) throw bad(code + " 必须为非负数值");
        }
        for (String code : List.of(HYSTERESIS_DEADBAND_PCT, CV_PCT, FB_PCT)) {
            Double value = envelope.featureOrNull(code);
            if (value != null && value > 100.0) throw bad(code + " 超出物理合理范围（0-100%）");
        }
    }

    @Override public boolean isOperatingSteady(Map<String, Object> opCondition) {
        // 非稳态（变载荷/开关过程）的死区与滞后读数无效，禁止进入评分与基线学习
        return Boolean.TRUE.equals(opCondition.get("steady_state"));
    }

    @Override public PluginDiagnosisResult processDiagnosis(Map<String, Double> current, Map<String, Double> base,
            Map<String, Object> metadataConfig) {
        double deadbandAttention = MechanismPluginStrategy.configValue(
                metadataConfig, DEADBAND_ATTENTION_RATIO_KEY, properties.deadbandAttentionRatio());
        double deadbandDanger = MechanismPluginStrategy.configValue(
                metadataConfig, DEADBAND_DANGER_RATIO_KEY, properties.deadbandDangerRatio());
        double leakAttention = MechanismPluginStrategy.configValue(
                metadataConfig, LEAK_ATTENTION_RMS_KEY, properties.leakAttentionRms());
        double leakDanger = MechanismPluginStrategy.configValue(
                metadataConfig, LEAK_DANGER_RMS_KEY, properties.leakDangerRms());

        double deadband = current.getOrDefault(HYSTERESIS_DEADBAND_PCT, 1.0);
        double baseDeadband = base.getOrDefault(HYSTERESIS_DEADBAND_PCT, 1.0);
        double leak = current.getOrDefault(ULTRASONIC_LEAK_RMS, 0.0);

        // 回程死区分级恶化倍率（分级阈值是刻意设计：验收用例 TC-MM-03 要求 k=3.5 时扣至 40 分）
        double k = deadband / Math.max(0.1, baseDeadband);
        double scoreDeadband = 100.0;
        if (k > deadbandDanger) scoreDeadband = 20.0;
        else if (k > deadbandAttention) scoreDeadband = 40.0;

        // 超声波高频泄漏能量打分（绝对阈值；击穿即硬限）
        double scoreLeak = 100.0;
        if (leak > leakDanger) scoreLeak = 30.0;
        else if (leak > leakAttention) scoreLeak = 65.0;

        // 可选机理特征：行程滞后与填料摩擦力（越高越差），缺失时满分
        Double lag = current.get(TRAVEL_TIME_LAG_MS);
        Double baseLag = base.get(TRAVEL_TIME_LAG_MS);
        boolean lagScored = lag != null && baseLag != null && baseLag > 1e-4;
        double scoreLag = lagScored ? HealthScale.relativeScore(lag, baseLag) : 100.0;
        Double friction = current.get(FRICTION_FORCE_KN);
        Double baseFriction = base.get(FRICTION_FORCE_KN);
        boolean frictionScored = friction != null && baseFriction != null && baseFriction > 1e-4;
        double scoreFriction = frictionScored ? HealthScale.relativeScore(friction, baseFriction) : 100.0;

        double staticScore = Math.min(Math.min(scoreDeadband, scoreLeak), Math.min(scoreLag, scoreFriction));
        Double baseLeak = base.get(ULTRASONIC_LEAK_RMS);
        List<HealthAssessment.FeatureContribution> contributions = List.of(
                new HealthAssessment.FeatureContribution("回环死区", deadband, baseDeadband, k, scoreDeadband),
                // 零基线时倍率无意义：置 null（不得写 Infinity/NaN 进证据文本与告警明细）
                new HealthAssessment.FeatureContribution("超声泄漏", leak, baseLeak,
                        baseLeak == null || baseLeak <= 0 ? null : leak / baseLeak, scoreLeak),
                new HealthAssessment.FeatureContribution("行程滞后", lag, baseLag,
                        lagScored ? lag / baseLag : null, scoreLag),
                new HealthAssessment.FeatureContribution("摩擦力", friction, baseFriction,
                        frictionScored ? friction / baseFriction : null, scoreFriction));

        boolean hardLimitBreached = leak > leakDanger;
        String defect;
        if (staticScore < 70.0 && scoreDeadband <= Math.min(scoreLeak, Math.min(scoreLag, scoreFriction))) {
            defect = String.format(Locale.ROOT, "调节阀动作迟滞卡阻 (死区扩大至 %.1f%%, 恶化倍率 k=%.2f): 填料压盖过紧或阀杆结垢卡阻",
                    deadband, k);
        } else if (staticScore < 70.0 && scoreLeak <= Math.min(scoreLag, scoreFriction)) {
            defect = "阀体内部严重空化冲刷或高压内漏 (超声声发射超标)";
        } else if (staticScore < 70.0 && scoreLag <= scoreFriction) {
            defect = String.format(Locale.ROOT, "阀门响应迟缓 (行程滞后 %.0fms), 检查气源压力与阀门定位器", lag == null ? 0.0 : lag);
        } else if (staticScore < 70.0) {
            defect = String.format(Locale.ROOT, "填料摩擦力增大 (%.2fkN), 检查填料压紧力与阀杆润滑", friction == null ? 0.0 : friction);
        } else {
            defect = "调节阀回路工作平稳";
        }
        return new PluginDiagnosisResult(staticScore, defect, hardLimitBreached, contributions);
    }

    /** 通道机理参数约束（与全局配置同一套）：按实际生效值校验 deadband danger > attention > 1、
     *  0 < leak attention < danger 且全部有限。 */
    @Override public void validateMetadata(Map<String, Object> metadata) {
        double attention = MechanismPluginStrategy.metadataValue(
                metadata, DEADBAND_ATTENTION_RATIO_KEY, properties.deadbandAttentionRatio());
        double danger = MechanismPluginStrategy.metadataValue(
                metadata, DEADBAND_DANGER_RATIO_KEY, properties.deadbandDangerRatio());
        double leakAttention = MechanismPluginStrategy.metadataValue(
                metadata, LEAK_ATTENTION_RMS_KEY, properties.leakAttentionRms());
        double leakDanger = MechanismPluginStrategy.metadataValue(
                metadata, LEAK_DANGER_RMS_KEY, properties.leakDangerRms());
        if (!Double.isFinite(attention) || !Double.isFinite(danger) || danger <= attention || attention <= 1.0)
            throw bad("metadata_config 死区倍率无效: 须满足 deadband_danger_ratio > deadband_attention_ratio > 1"
                    + "（未填写的键沿用全局配置）");
        if (!Double.isFinite(leakAttention) || !Double.isFinite(leakDanger)
                || leakAttention <= 0 || leakDanger <= leakAttention)
            throw bad("metadata_config 泄漏阈值无效: 须满足 0 < leak_attention_rms < leak_danger_rms（未填写的键沿用全局配置）");
    }

    @Override public List<MetadataField> metadataFields() {
        return List.of(
                new MetadataField(DEADBAND_ATTENTION_RATIO_KEY, "死区关注倍率",
                        "回环死区超出基线该倍率进入关注档", properties.deadbandAttentionRatio()),
                new MetadataField(DEADBAND_DANGER_RATIO_KEY, "死区危险倍率",
                        "回环死区超出基线该倍率判危险档", properties.deadbandDangerRatio()),
                new MetadataField(LEAK_ATTENTION_RMS_KEY, "泄漏关注阈值",
                        "超声泄漏 RMS 超出该值进入关注档", properties.leakAttentionRms()),
                new MetadataField(LEAK_DANGER_RMS_KEY, "泄漏危险阈值",
                        "超声泄漏 RMS 超出该值判危险并视为硬限击穿", properties.leakDangerRms()));
    }

    @Override protected String adviceFor(PluginDiagnosisResult result) {
        return result.isHardLimitBreached()
                ? "立即安排阀体内漏检测与在线超声波复测，必要时切换旁路并解体检修。"
                : "复测 CV-FB 迟滞回环并比对基线，检查填料压紧力、阀杆结垢与定位器参数。";
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
