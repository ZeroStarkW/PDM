package com.pdm.mvp.mcsa;

import com.pdm.mvp.analytics.HealthAssessment;
import com.pdm.mvp.analytics.HealthScale;
import com.pdm.mvp.asset.DeviceTypeService;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.diagnostics.DiagnosticMatch;
import com.pdm.mvp.modality.BaselineSnapshot;
import com.pdm.mvp.modality.ModalityPlugin;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * MCSA（电机电流特征分析）试点插件。
 * 特征集：工频、转差率、负载率与工频边频带/载波幅值（dB）。
 * 负载率只读工况（operating_condition.load_pct / load_ratio）：契约适配器在指标缺少 load_pct 时用工况负载补入特征。
 * 门禁：仅稳态且负载在配置区间内的样本进入评分，其余返回不可评估，不产生确定性结论。
 * 评分：边频带/载波功率比相对基线评分；绝对阈值默认关闭（alertsEnabled=false），
 * 现场校准验收后开启，启用 54/40 dB 候选阈值并做转子导条损伤归因。
 */
@Component
public class McsaPlugin implements ModalityPlugin {
    public static final String MAINS_FREQ_HZ = "mains_freq_hz";
    public static final String SLIP_PCT = "slip_pct";
    public static final String LOAD_PCT = "load_pct";
    public static final String SIDEBAND_AMP_DB = "sideband_amp_db";
    public static final String CARRIER_AMP_DB = "carrier_amp_db";
    /**
     * 派生特征：边频带/载波线性幅值比 10^((sideband−carrier)/20)。
     * 取幅值比而非功率比，使典型量级（约 1e-4~1e-2）落在共享相对评分曲线的基线守卫范围内。
     */
    public static final String SIDEBAND_RATIO = "sideband_ratio";

    private final McsaProperties properties;
    private final DeviceTypeService deviceTypes;

    public McsaPlugin(McsaProperties properties, DeviceTypeService deviceTypes) {
        this.properties = properties;
        this.deviceTypes = deviceTypes;
    }

    @Override public String modalityType() { return ModalityTypes.MCSA; }

    @Override public Set<String> requiredFeatures() {
        return Set.of(MAINS_FREQ_HZ, SLIP_PCT, LOAD_PCT, SIDEBAND_AMP_DB, CARRIER_AMP_DB);
    }

    @Override public void validateFeatures(TelemetryEnvelope envelope) {
        double slip = envelope.feature(SLIP_PCT);
        if (slip < 0 || slip > properties.slipMaxPct())
            throw bad("slip_pct 超出物理合理范围（0-" + properties.slipMaxPct() + "%）");
    }

    @Override public ConditionGate gate(TelemetryEnvelope envelope) {
        var condition = envelope.condition();
        Boolean steadyState = condition == null ? null : condition.steadyState();
        Double loadPct = condition == null ? null : condition.loadPct();
        if (steadyState == null || !steadyState || loadPct == null
                || loadPct < properties.loadMinPct() || loadPct > properties.loadMaxPct())
            return ConditionGate.notAssessable("负载波动或非稳态工况，暂不可评估");
        return ConditionGate.accept();
    }

    @Override public Set<String> spikeFeatureCodes() { return Set.of(SIDEBAND_RATIO); }

    @Override public Double spikeFeatureValue(TelemetryEnvelope envelope, String code) {
        return SIDEBAND_RATIO.equals(code) ? sidebandRatio(envelope) : envelope.featureOrNull(code);
    }

    @Override public Set<String> baselineFeatureCodes() { return Set.of(SIDEBAND_RATIO, CARRIER_AMP_DB); }

    @Override public Set<String> scoringBaselineFeatures() { return Set.of(SIDEBAND_RATIO); }

    /** 时序查询特征列：仅包含实际写入时序库的指标（派生 sideband_ratio 不落库，列入只会触发列缺失重试）。 */
    @Override public Set<String> seriesFeatureCodes() {
        return Set.of(MAINS_FREQ_HZ, SLIP_PCT, LOAD_PCT, SIDEBAND_AMP_DB, CARRIER_AMP_DB);
    }

    @Override public String triggerFeatureCode() { return SIDEBAND_RATIO; }

    @Override public String trendFeatureCode() { return SIDEBAND_AMP_DB; }

    @Override public Map<String, Double> baselineFeatureValues(TelemetryEnvelope envelope) {
        Map<String, Double> values = new LinkedHashMap<>();
        values.put(SIDEBAND_RATIO, sidebandRatio(envelope));
        values.put(CARRIER_AMP_DB, envelope.feature(CARRIER_AMP_DB));
        return values;
    }

    @Override public DiagnosticMatch diagnose(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline) {
        double deltaDb = sample.feature(CARRIER_AMP_DB) - sample.feature(SIDEBAND_AMP_DB);
        var type = point.getDevice().getDeviceType() == null ? deviceTypes.general() : point.getDevice().getDeviceType();
        String evidence = "工频边频带间距 " + String.format(java.util.Locale.ROOT, "%.1f", deltaDb)
                + " dB（候选阈值 " + properties.sidebandWarnDb() + "/" + properties.sidebandDangerDb()
                + " dB，待现场校准）";
        if (!properties.alertsEnabled())
            return new DiagnosticMatch(type.getId(), type.getTypeName(), null, null, null, null, null, deltaDb,
                    null, null, null, "边频带相对基线变化（未启用确定性归因）", evidence,
                    "MCSA 告警阈值未启用：先完成健康/故障样本离线比对与现场试点验收。");
        if (deltaDb <= properties.sidebandWarnDb())
            return new DiagnosticMatch(type.getId(), type.getTypeName(), null, null, null, null,
                    properties.sidebandWarnDb(), deltaDb, null, null, null,
                    "转子导条损伤疑似（工频边频带增大）", evidence,
                    "结合负载记录与现场校准复核，排查转子导条与端环。");
        return new DiagnosticMatch(type.getId(), type.getTypeName(), null, null, null, null, null, deltaDb,
                null, null, null, "电流边频带处于正常范围", evidence, "维持周期监测。");
    }

    @Override public HealthAssessment score(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline,
            DiagnosticMatch diagnosis) {
        double relative = HealthScale.relativeScore(sidebandRatio(sample), baseline.base(SIDEBAND_RATIO));
        double deltaDb = sample.feature(CARRIER_AMP_DB) - sample.feature(SIDEBAND_AMP_DB);
        double absolute = properties.alertsEnabled() ? absoluteFromDelta(deltaDb) : 100.0;
        double hi = Math.clamp(Math.min(relative, absolute), 0.0, 100.0);
        String level = HealthScale.levelOf(hi);
        return new HealthAssessment(relative, absolute, hi, level, HealthScale.statusCodeOf(level), diagnosis);
    }

    @Override public AlertTrigger alertTrigger(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline) {
        Double base = baseline.base(SIDEBAND_RATIO);
        return new AlertTrigger(sidebandRatio(sample), base == null ? 0.0 : base);
    }

    @Override public boolean alertsEnabled() { return properties.alertsEnabled(); }

    /** 可选负载分箱：测点配置 load_bins（2..10）时按负载率等分独立学习；未配置恒为 0（零回归）。 */
    @Override public short resolveBinIndex(com.pdm.mvp.asset.PointEntity point, TelemetryEnvelope sample) {
        return com.pdm.mvp.analytics.OperatingRegimeBinner.resolveLoadBin(point, sample);
    }

    @Override public void validateMetadata(Map<String, Object> metadata) {
        Object value = metadata == null ? null : metadata.get("load_bins");
        if (value == null) return;
        if (!(value instanceof Number n)) throw bad("load_bins 必须为 1-10 之间的整数");
        int bins = n.intValue();
        if (bins < 1 || bins > 10 || Double.compare(n.doubleValue(), bins) != 0)
            throw bad("load_bins 必须为 1-10 之间的整数");
    }

    /** 边频带/载波线性幅值比：10^((sideband_db - carrier_db)/20)。 */
    static double sidebandRatio(TelemetryEnvelope envelope) {
        double deltaDb = envelope.feature(CARRIER_AMP_DB) - envelope.feature(SIDEBAND_AMP_DB);
        return Math.pow(10.0, -deltaDb / 20.0);
    }

    /** 候选绝对评分：间距 ≤40 dB 高风险，≤54 dB 关注，其余满分（须现场校准）。 */
    private double absoluteFromDelta(double deltaDb) {
        if (deltaDb <= properties.sidebandDangerDb()) return 20.0;
        if (deltaDb <= properties.sidebandWarnDb()) return 50.0;
        return 100.0;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
