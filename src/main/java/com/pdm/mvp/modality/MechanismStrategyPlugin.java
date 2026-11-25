package com.pdm.mvp.modality;

import com.pdm.mvp.analytics.DegradationTracker;
import com.pdm.mvp.analytics.HealthAssessment;
import com.pdm.mvp.analytics.HealthScale;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.diagnostics.DiagnosticMatch;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 机理策略插件基类（文档 §4.2/§4.4 共享微内核的插件侧实现）：
 * 劣化跟踪（dk/dt）、诊断组装（η_dyn 突增前缀与贡献摘要）、统一评分与告警触发幅值 100% 复用，
 * 子类只实现纯机理评分 {@link #processDiagnosis}、处置建议与告警开关。
 */
public abstract class MechanismStrategyPlugin implements MechanismPluginStrategy {

    private static final double EPSILON = 1e-4;
    /** DB 列宽：dominant_factor VARCHAR(128)、diagnostic_evidence VARCHAR(512)。 */
    private static final int FACTOR_LIMIT = 128;
    private static final int EVIDENCE_LIMIT = 512;
    /** η_dyn 低于该阈值视为劣化突增，诊断因子加警告前缀（与文档 §4.4 一致）。 */
    private static final double AVALANCHE_ETA_THRESHOLD = 0.7;
    /** 静态分健康线：静态分仍 ≥70 但 η_dyn 突增时改用雪崩归因，避免“警告 … | 机构特性良好”的自相矛盾。 */
    private static final double HEALTHY_STATIC_SCORE = 70.0;
    /** 窗口内 k 的变化量下限（相对评分曲线死区宽度 1.15−1.0）：不足则视为无劣化趋势，噪声不得触发 η_dyn。 */
    static final double MATERIAL_CHANGE = 0.15;
    /** 连续型模态观测抽稀间隔：每测点至多 5 分钟一条，512 个样本覆盖 ≥42 小时。 */
    static final Duration CONTINUOUS_OBSERVE_INTERVAL = Duration.ofMinutes(5);
    /** 事件型 / 连续型拟合所需最少样本数；事件型样本天然稀疏，连续型经抽稀后按窗口容量取。 */
    static final int EVENT_MIN_SAMPLES = 8;
    static final int CONTINUOUS_MIN_SAMPLES = 24;
    private static final String AVALANCHE_ADVICE = "缩短监测周期并安排复测，确认劣化速率是否持续加快后再确定检修窗口。";

    private final Duration defaultDegradationWindow;
    /** 惰性构建：子类配置字段在超类构造期尚未赋值，首次使用时再按插件声明的节律与劣化窗口构建。 */
    private DegradationTracker degradation;
    /** 连续型模态每测点上次入窗时间戳（抽稀用）。 */
    private final Map<Long, Long> lastObservedAt = new HashMap<>();

    protected MechanismStrategyPlugin(Duration defaultDegradationWindow) {
        if (defaultDegradationWindow != null && !defaultDegradationWindow.isPositive())
            throw new IllegalArgumentException("劣化窗口必须为正时长");
        this.defaultDegradationWindow = defaultDegradationWindow;
    }

    /** 按节律构建劣化跟踪器：事件型 EVENT_MIN_SAMPLES 条且跨度 ≥ 窗口/12；连续型 CONTINUOUS_MIN_SAMPLES 条且跨度 ≥ 窗口/2。 */
    private synchronized DegradationTracker degradation() {
        if (degradation == null) {
            Duration window = degradationWindow(defaultDegradationWindow);
            boolean event = cadence() == Cadence.EVENT;
            degradation = new DegradationTracker(window,
                    event ? EVENT_MIN_SAMPLES : CONTINUOUS_MIN_SAMPLES,
                    event ? window.dividedBy(12) : window.dividedBy(2),
                    DegradationTracker.DEFAULT_MAX_SAMPLES);
        }
        return degradation;
    }

    /** 仅以收敛特征的 worst-ratio 入窗；连续型模态按 5 分钟抽稀，事件型不抽稀。 */
    @Override public synchronized void observeDegradation(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline) {
        Set<String> codes = convergenceFeatureCodes();
        if (codes == null) return;
        double worstRatio = 0.0;
        for (String code : codes) {
            Double current = sample.featureOrNull(code);
            Double base = baseline.base(code);
            if (current == null || base == null || base <= EPSILON) continue;
            double ratio = lowerIsWorseFeatures().contains(code)
                    ? current <= EPSILON ? Double.NaN : base / current
                    : current / base;
            if (!Double.isNaN(ratio)) worstRatio = Math.max(worstRatio, ratio);
        }
        if (worstRatio <= 0.0) return;
        if (cadence() != Cadence.EVENT && !observeSlotFree(point.getId(), sample.timestamp())) return;
        degradation().observe(point.getId(), sample.timestamp(), worstRatio);
    }

    /** 抽稀判定：距该测点上次入窗不足 5 分钟则丢弃本次观测。 */
    private boolean observeSlotFree(Long pointId, long timestamp) {
        Long last = lastObservedAt.get(pointId);
        if (last != null && timestamp - last < CONTINUOUS_OBSERVE_INTERVAL.toMillis()) return false;
        lastObservedAt.put(pointId, timestamp);
        return true;
    }

    @Override public synchronized void resetRuntimeState(Long pointId) {
        degradation().reset(pointId);
        lastObservedAt.remove(pointId);
    }

    /**
     * 当前测点用于评分的 dk/dt 拟合斜率（Δk/24h）：窗口内 k 的变化量不足 {@link #MATERIAL_CHANGE} 时视为 0
     * （样本噪声主导的斜率不得启动 η_dyn 抑制）。
     */
    protected double slope(Long pointId) {
        DegradationTracker tracker = degradation();
        return tracker.ratioRange(pointId) >= MATERIAL_CHANGE ? tracker.slope(pointId).betaPerDay() : 0.0;
    }

    @Override public DiagnosticMatch diagnose(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline) {
        var result = processDiagnosis(sample.features(), baseline.featureBase(), point.metadataConfig());
        double slope = slope(point.getId());
        boolean avalanche = DegradationTracker.etaDyn(slope) < AVALANCHE_ETA_THRESHOLD;
        String factor = result.defectAttribution();
        String advice = adviceFor(result);
        if (avalanche && result.staticHealthScore() >= HEALTHY_STATIC_SCORE) {
            factor = String.format(Locale.ROOT, "劣化突发加速 (dk/dt=%.2f/24h), 建议缩短监测周期并复测", slope);
            advice = AVALANCHE_ADVICE;
        } else if (avalanche) {
            factor = String.format(Locale.ROOT, "警告: 劣化速率突增 (dk/dt=%.2f/24h) | %s", slope, factor);
        }
        var device = point.getDevice();
        var deviceType = device == null ? null : device.getDeviceType();
        return new DiagnosticMatch(deviceType == null ? null : deviceType.getId(),
                deviceType == null ? "通用设备" : deviceType.getTypeName(),
                null, null, null, null, null, sample.featureOrNull(primaryFeatureCode()),
                null, null, null, truncate(factor, FACTOR_LIMIT), evidence(result),
                truncate(advice, EVIDENCE_LIMIT));
    }

    @Override public HealthAssessment score(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline,
            DiagnosticMatch diagnosis) {
        var result = processDiagnosis(sample.features(), baseline.featureBase(), point.metadataConfig());
        double slope = slope(point.getId());
        double eta = DegradationTracker.etaDyn(slope);
        double finalScore = Math.round(Math.clamp(result.staticHealthScore() * eta, 0.0, 100.0) * 10.0) / 10.0;
        String level = HealthScale.levelOf(finalScore, result.isHardLimitBreached());
        return new HealthAssessment(result.staticHealthScore(), result.staticHealthScore(), finalScore, level,
                HealthScale.statusCodeOf(level), diagnosis, slope, eta, result.contributions(),
                result.isHardLimitBreached());
    }

    @Override public AlertTrigger alertTrigger(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline) {
        Double base = baseline.base(primaryFeatureCode());
        return new AlertTrigger(sample.feature(primaryFeatureCode()), base == null ? 0.0 : base);
    }

    /** 子类提供的处置建议（写入诊断建议列与告警通知）。 */
    protected abstract String adviceFor(PluginDiagnosisResult result);

    /** 贡献明细摘要：特征、当前值、基线、倍率与得分；缺失或非有限值（如零基线倍率）以“—”显示，超长截断。 */
    private String evidence(PluginDiagnosisResult result) {
        StringBuilder text = new StringBuilder();
        for (var contribution : result.contributions()) {
            if (text.length() > 0) text.append("; ");
            text.append(contribution.feature())
                    .append(" 当前 ").append(display(contribution.current()))
                    .append("/基线 ").append(display(contribution.base()))
                    .append(" (倍率 ").append(display(contribution.ratio()))
                    .append(", 得分 ").append(String.format(Locale.ROOT, "%.0f", contribution.score()))
                    .append(")");
        }
        return truncate(text.toString(), EVIDENCE_LIMIT);
    }

    /** 数值展示：4 位有效数字并去掉尾随零（40 / 1.15 / 0.0004）；null 与 NaN/Infinity 显示“—”。 */
    static String display(Double value) {
        if (value == null || !Double.isFinite(value)) return "—";
        String text = String.format(Locale.ROOT, "%.4g", value);
        int dot = text.indexOf('.');
        if (dot < 0 || text.indexOf('e') >= 0) return text;
        int end = text.length();
        while (end > dot + 1 && text.charAt(end - 1) == '0') end--;
        if (text.charAt(end - 1) == '.') end--;
        return text.substring(0, end);
    }

    /** 截断到列宽；不切断代理对（避免半截字符写入 UTF-8 列）。 */
    static String truncate(String text, int limit) {
        if (text == null || text.length() <= limit) return text;
        int end = limit;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
}
