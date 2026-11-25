package com.pdm.mvp.analytics;

import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.diagnostics.DiagnosticEvaluator;
import com.pdm.mvp.diagnostics.DiagnosticMatch;
import com.pdm.mvp.modality.BaselineSnapshot;
import com.pdm.mvp.modality.ModalityPlugin;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import com.pdm.mvp.telemetry.VibrationContractAdapter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 旋转机械振动插件：承载现有振动诊断与评分实现，旧行为等价
 * （停机判据、尖峰键、相对/绝对评分与告警触发幅值全部沿用原逻辑）。
 */
@Component
public class RotatingVibrationPlugin implements ModalityPlugin {
    static final double STOPPED_V_RMS = 0.05;
    static final double STOPPED_RPM = 300.0;
    /** 连续 1Hz 采样抽稀间隔：每测点至多 5 分钟入窗一条，使 512 容量覆盖 ≥42 小时窗口。 */
    static final long CONTINUOUS_OBSERVE_INTERVAL_MS = java.time.Duration.ofMinutes(5).toMillis();
    /** 窗口内 k(t) 实质性变化下限（与 15% 死区对齐）：抑制稳态传感器微噪触发虚假 η_dyn。 */
    static final double MATERIAL_CHANGE = 0.15;

    private final HealthIndexCalculator calculator;
    private final DiagnosticEvaluator diagnostics;
    private final DegradationTracker degradation;
    private final Map<Long, Long> lastObservedAt = new java.util.HashMap<>();

    public RotatingVibrationPlugin(HealthIndexCalculator calculator, DiagnosticEvaluator diagnostics,
            DegradationTracker degradation) {
        this.calculator = calculator;
        this.diagnostics = diagnostics;
        this.degradation = degradation;
    }

    @Override public String modalityType() { return ModalityTypes.ROTATING_VIB; }

    /** 进入评分的必需特征；a_peak 为可选特征（协议 2.1.0 旋转振动样本可不带），存在时仍参与学习。 */
    @Override public Set<String> requiredFeatures() {
        return Set.of(VibrationContractAdapter.V_RMS, VibrationContractAdapter.A_KURTOSIS,
                VibrationContractAdapter.FREQ_1X_AMP, VibrationContractAdapter.FREQ_2X_AMP);
    }

    /** 特征域校验：只校验出现的特征（必填性由契约校验先行保证），a_peak 缺失不得抛异常。 */
    @Override public void validateFeatures(TelemetryEnvelope envelope) {
        for (String code : List.of(VibrationContractAdapter.V_RMS, VibrationContractAdapter.A_PEAK,
                VibrationContractAdapter.FREQ_1X_AMP, VibrationContractAdapter.FREQ_2X_AMP)) {
            Double value = envelope.featureOrNull(code);
            if (value != null && value < 0) throw bad("振动幅值必须为有限且非负数值");
        }
        Double kurtosis = envelope.featureOrNull(VibrationContractAdapter.A_KURTOSIS);
        if (kurtosis != null && kurtosis < 0) throw bad("a_kurtosis 必须为有限非负数值");
    }

    @Override public ConditionGate gate(TelemetryEnvelope envelope) {
        var condition = envelope.condition();
        Double rpm = condition == null ? null : condition.rpm();
        boolean stopped = envelope.feature(VibrationContractAdapter.V_RMS) < STOPPED_V_RMS
                || (rpm != null && rpm < STOPPED_RPM);
        if (stopped) return ConditionGate.stoppedGate();
        // 工况显式声明非稳态（文档 §4.1：is_steady=false）时不评估；旧振动载荷不带该标志（null）保持原行为
        if (condition != null && Boolean.FALSE.equals(condition.steadyState()))
            return ConditionGate.notAssessable("非稳态工况，暂不可评估");
        return ConditionGate.accept();
    }

    @Override public Set<String> spikeFeatureCodes() {
        return Set.of(VibrationContractAdapter.V_RMS, VibrationContractAdapter.FREQ_1X_AMP);
    }

    @Override public Set<String> baselineFeatureCodes() {
        return Set.of(VibrationContractAdapter.FREQ_1X_AMP, VibrationContractAdapter.FREQ_2X_AMP,
                VibrationContractAdapter.V_RMS, VibrationContractAdapter.A_PEAK,
                VibrationContractAdapter.ENVELOPE_BPFO_AMP, VibrationContractAdapter.TEMPERATURE);
    }

    @Override public Set<String> scoringBaselineFeatures() {
        return Set.of(VibrationContractAdapter.FREQ_1X_AMP, VibrationContractAdapter.V_RMS);
    }

    @Override public Map<String, Double> baselineFeatureValues(TelemetryEnvelope envelope) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (String code : baselineFeatureCodes()) {
            Double value = envelope.featureOrNull(code);
            if (value != null) values.put(code, value);
        }
        return values;
    }

    @Override public DiagnosticMatch diagnose(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline) {
        return diagnostics.evaluate(point, VibrationContractAdapter.toRequest(sample), baseline.base(VibrationContractAdapter.FREQ_1X_AMP));
    }

    @Override public synchronized void observeDegradation(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline) {
        double worstRatio = 0.0;
        for (String code : List.of(VibrationContractAdapter.FREQ_1X_AMP, VibrationContractAdapter.FREQ_2X_AMP,
                VibrationContractAdapter.ENVELOPE_BPFO_AMP, VibrationContractAdapter.ENVELOPE_BPFI_AMP)) {
            Double current = sample.featureOrNull(code);
            Double base = baseline.base(code);
            if (current == null || base == null || base <= 1e-4) continue;
            worstRatio = Math.max(worstRatio, current / base);
        }
        if (worstRatio <= 0.0) return;
        Long pointId = point.getId();
        Long last = lastObservedAt.get(pointId);
        if (last != null && sample.timestamp() - last < CONTINUOUS_OBSERVE_INTERVAL_MS) return;
        lastObservedAt.put(pointId, sample.timestamp());
        degradation.observe(pointId, sample.timestamp(), worstRatio);
    }

    @Override public synchronized void resetRuntimeState(Long pointId) {
        degradation.reset(pointId);
        lastObservedAt.remove(pointId);
    }

    @Override public HealthAssessment score(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline,
            DiagnosticMatch diagnosis) {
        double slope = effectiveSlope(point == null ? null : point.getId());
        HealthIndexCalculator.IsoClass isoClass = HealthIndexCalculator.IsoClass.from(
                point == null || point.getDevice() == null ? null : point.getDevice().getIsoClass());
        HealthAssessment assessment = calculator.calculate(sample, baseline, slope, diagnosis, isoClass);
        // 无规则命中时以确定性根因阶梯覆盖兜底文案（文档 §6.2）
        if (diagnosis.ruleId() == null) {
            var verdict = DefectClassifier.classify(assessment);
            if (verdict.isPresent()) {
                var v = verdict.get();
                diagnosis = new DiagnosticMatch(diagnosis.deviceTypeId(), diagnosis.deviceTypeName(), null, null,
                        v.metric(), null, null, null, null, null, null,
                        v.defect(), v.evidence(), v.advice());
                assessment = new HealthAssessment(assessment.hiRelative(), assessment.hiAbsolute(),
                        assessment.healthIndex(), assessment.level(), assessment.statusCode(), diagnosis,
                        assessment.slopeDkDt(), assessment.etaDyn(), assessment.contributions());
            }
        }
        return assessment;
    }

    private synchronized double effectiveSlope(Long pointId) {
        return degradation.ratioRange(pointId) >= MATERIAL_CHANGE ? degradation.slope(pointId).betaPerDay() : 0.0;
    }

    @Override public AlertTrigger alertTrigger(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline) {
        Double base = baseline.base(VibrationContractAdapter.FREQ_1X_AMP);
        return new AlertTrigger(sample.feature(VibrationContractAdapter.FREQ_1X_AMP), base == null ? 0.0 : base);
    }

    @Override public String triggerFeatureCode() { return VibrationContractAdapter.FREQ_1X_AMP; }

    @Override public String trendFeatureCode() { return VibrationContractAdapter.V_RMS; }

    @Override public boolean alertsEnabled() { return true; }

    /** 多工况分箱：偏离额定转速 ±5% 的变频稳态样本自动按转速段分箱独立学习（额定箱仍为 0）。 */
    @Override public short resolveBinIndex(PointEntity point, TelemetryEnvelope sample) {
        return OperatingRegimeBinner.resolveVibrationBin(point, sample);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
