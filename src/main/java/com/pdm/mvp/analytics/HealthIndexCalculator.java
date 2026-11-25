package com.pdm.mvp.analytics;

import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.diagnostics.DiagnosticMatch;
import com.pdm.mvp.modality.BaselineSnapshot;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import com.pdm.mvp.telemetry.VibrationContractAdapter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 综合健康指数评分（文档 §4.4 加权短板法 + §4.3 动力学抑制）：
 * 1) 各机理特征相对基线 Sigmoid 分（15% 死区）取短板；
 * 2) ISO 10816 振动烈度绝对门限兜底（文档曲线）；
 * 3) 温升（轴承−环境）相对温升基线的热传导补偿评分；
 * 4) HI_final = min(1X, 2X, BPFO, BPFI, ISO, Temp) × η_dyn，η_dyn 由 dk/dt 斜率决定。
 */
@Component
public class HealthIndexCalculator {

    /** ISO 10816 机组烈度分类阈值（mm/s RMS，按 ISO R20 4dB 台阶序列：B/C 告警线与 C/D 危险线）。 */
    public enum IsoClass {
        CLASS_I(1.8, 2.8),
        CLASS_II(2.8, 4.5),
        CLASS_III(4.5, 7.1),
        CLASS_IV(7.1, 11.2);

        private final double warnRms;
        private final double dangerRms;

        IsoClass(double warnRms, double dangerRms) {
            this.warnRms = warnRms;
            this.dangerRms = dangerRms;
        }

        public double warnRms() { return warnRms; }
        public double dangerRms() { return dangerRms; }

        /** 解析设备配置的分类编码；为空或未知值回退默认 CLASS_II。 */
        public static IsoClass from(String code) {
            if (code == null || code.isBlank()) return CLASS_II;
            try {
                return IsoClass.valueOf(code.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return CLASS_II;
            }
        }

        /** 校验并归一化 API 输入的分类编码，非法抛 IllegalArgumentException。 */
        public static String normalizeStrict(String code) {
            if (code == null || code.isBlank()) throw new IllegalArgumentException("iso_class 不能为空");
            return IsoClass.valueOf(code.trim().toUpperCase(java.util.Locale.ROOT)).name();
        }
    }

    public HealthAssessment calculate(TelemetryEnvelope sample, BaselineSnapshot baseline, double slopePerDay,
            DiagnosticMatch diagnosis) {
        return calculate(sample, baseline, slopePerDay, diagnosis, IsoClass.CLASS_II);
    }

    public HealthAssessment calculate(TelemetryEnvelope sample, BaselineSnapshot baseline, double slopePerDay,
            DiagnosticMatch diagnosis, IsoClass isoClass) {
        IsoClass effectiveIso = isoClass == null ? IsoClass.CLASS_II : isoClass;
        List<HealthAssessment.FeatureContribution> contributions = new ArrayList<>();

        double score1x = addRelativeScore(contributions, "1X谐波",
                sample.featureOrNull(VibrationContractAdapter.FREQ_1X_AMP),
                baseline.base(VibrationContractAdapter.FREQ_1X_AMP));
        double score2x = addRelativeScore(contributions, "2X谐波",
                sample.featureOrNull(VibrationContractAdapter.FREQ_2X_AMP),
                baseline.base(VibrationContractAdapter.FREQ_2X_AMP));
        double scoreBpfo = addRelativeScore(contributions, "BPFO外圈包络",
                sample.featureOrNull(VibrationContractAdapter.ENVELOPE_BPFO_AMP),
                baseline.base(VibrationContractAdapter.ENVELOPE_BPFO_AMP));
        double scoreBpfi = addRelativeScore(contributions, "BPFI内圈包络",
                sample.featureOrNull(VibrationContractAdapter.ENVELOPE_BPFI_AMP),
                baseline.base(VibrationContractAdapter.ENVELOPE_BPFI_AMP));

        double relative = Double.min(Double.min(score1x, score2x), Double.min(scoreBpfo, scoreBpfi));

        // 绝对约束：ISO 10816 按设备机组分类（CLASS_I~IV）动态解算烈度门限兜底
        double vRms = sample.feature(VibrationContractAdapter.V_RMS);
        double scoreIso = absoluteIsoScore(vRms, effectiveIso);
        double warnRms = effectiveIso.warnRms();
        contributions.add(new HealthAssessment.FeatureContribution("ISO10816烈度", vRms, warnRms, vRms / warnRms, scoreIso));

        // 环境温升热传导补偿：轴承温度 − 环境温度 相对温升基线
        Double tempBearing = sample.featureOrNull(VibrationContractAdapter.TEMPERATURE);
        Double tempAmbient = sample.featureOrNull(VibrationContractAdapter.TEMP_AMBIENT);
        Double baseTempRise = baseline.base(VibrationContractAdapter.TEMP_RISE);
        double scoreTemp = 100.0;
        if (tempBearing != null && tempAmbient != null && baseTempRise != null && baseTempRise > 1.0) {
            double rise = Math.max(0.0, tempBearing - tempAmbient);
            double ratio = rise / baseTempRise;
            if (ratio > 1.8) scoreTemp = 30.0;
            else if (ratio > 1.3) scoreTemp = Math.max(30.0, 70.0 - (ratio - 1.3) * 80.0);
            contributions.add(new HealthAssessment.FeatureContribution("温升", rise, baseTempRise, ratio, scoreTemp));
        } else if (tempBearing != null && baseline.base(VibrationContractAdapter.TEMPERATURE) != null) {
            // 回退：无环境温度时沿用旧绝对温度相对评分
            scoreTemp = addRelativeScore(contributions, "轴承温度", tempBearing,
                    baseline.base(VibrationContractAdapter.TEMPERATURE));
        }

        // 动态雪崩抑制乘子
        double etaDyn = DegradationTracker.etaDyn(slopePerDay);

        double staticMin = Math.min(relative, Math.min(scoreIso, scoreTemp));
        double hi = Math.clamp(staticMin * etaDyn, 0.0, 100.0);
        String level = HealthScale.levelOf(hi);
        return new HealthAssessment(relative, scoreIso, hi, level, HealthScale.statusCodeOf(level), diagnosis,
                slopePerDay, etaDyn, contributions);
    }

    /** ISO 10816 默认 CLASS_II 曲线（文档 §6.2）：≤2.8 满分；2.8~4.5 线性降至 45；>4.5 快速衰减，下限 10。 */
    static double absoluteIsoScore(double vRms) {
        return absoluteIsoScore(vRms, IsoClass.CLASS_II);
    }

    /** 按 ISO 10816 机组分类（CLASS_I~IV）评分：≤warn 满分；warn~danger 线性 70→45；>danger 衰减，下限 10。 */
    static double absoluteIsoScore(double vRms, IsoClass isoClass) {
        IsoClass effective = isoClass == null ? IsoClass.CLASS_II : isoClass;
        double warn = effective.warnRms();
        double danger = effective.dangerRms();
        if (vRms > danger) return Math.max(10.0, 30.0 - (vRms - danger) * 10.0);
        if (vRms > warn) return 70.0 - ((vRms - warn) / (danger - warn)) * 25.0;
        return 100.0;
    }

    private static double addRelativeScore(List<HealthAssessment.FeatureContribution> contributions,
            String label, Double current, Double base) {
        if (current == null || base == null) return 100.0;
        double score = HealthScale.relativeScore(current, base);
        contributions.add(new HealthAssessment.FeatureContribution(label, current, base, current / base, score));
        return score;
    }
}
