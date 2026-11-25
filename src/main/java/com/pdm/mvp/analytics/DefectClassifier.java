package com.pdm.mvp.analytics;

import com.pdm.mvp.diagnostics.DiagnosticMetric;
import java.util.List;
import java.util.Optional;

/**
 * 确定性故障根因推断（文档 §6.2 第 6 步阶梯，扩展 BPFI 内圈）：
 * 雪崩突发劣化 &gt; 1X 不平衡 &gt; 2X 不对中 &gt; BPFO 外圈点蚀 &gt; BPFI 内圈点蚀
 * &gt; ISO 烈度击穿 &gt; 温升异常 &gt; 机组健康运转。
 * 判定依据：短板特征得分等于综合最低分且低于 70 分，或动力学乘子显著抑制。
 */
public final class DefectClassifier {
    private static final double AVALANCHE_ETA_THRESHOLD = 0.7;
    private static final double DEFECT_SCORE_CEILING = 70.0;
    private static final double EPSILON = 1e-6;

    private DefectClassifier() {}

    public record Verdict(String defect, String evidence, String advice, DiagnosticMetric metric) {}

    public static Optional<Verdict> classify(HealthAssessment assessment) {
        if (assessment.etaDyn() < AVALANCHE_ETA_THRESHOLD) {
            return Optional.of(new Verdict(
                    String.format("劣化突发加速 (dk/dt=%.2f/24h), 存雪崩断裂风险", assessment.slopeDkDt()),
                    String.format("动力学抑制乘子 η_dyn=%.3f, 综合得分被强制压制至 %.1f", assessment.etaDyn(), assessment.healthIndex()),
                    "立即降载运行并安排频谱复测，重点检查裂纹类突发缺陷，必要时停机检查。",
                    null));
        }
        List<HealthAssessment.FeatureContribution> contributions = assessment.contributions();
        if (contributions.isEmpty()) return Optional.empty();
        double minScore = contributions.stream().mapToDouble(HealthAssessment.FeatureContribution::score).min().orElse(100.0);
        if (minScore >= DEFECT_SCORE_CEILING) return Optional.empty();
        for (HealthAssessment.FeatureContribution contribution : contributions) {
            if (Math.abs(contribution.score() - minScore) > EPSILON) continue;
            Verdict verdict = verdictFor(contribution);
            if (verdict != null) return Optional.of(verdict);
        }
        return Optional.empty();
    }

    private static Verdict verdictFor(HealthAssessment.FeatureContribution contribution) {
        String ratioText = contribution.ratio() == null ? "-" : String.format("%.2f", contribution.ratio());
        String evidence = String.format("%s 当前 %.3f (基线 %.3f), 恶化倍率 k=%s, 特征得分 %.1f",
                contribution.feature(), nvl(contribution.current()), nvl(contribution.base()), ratioText, contribution.score());
        return switch (contribution.feature()) {
            case "1X谐波" -> new Verdict("转子动不平衡 (1X频带能量恶化)", evidence,
                    "检查转子动平衡状态、联轴器对中与地脚螺栓紧固，安排现场动平衡校正。", DiagnosticMetric.FREQ_1X_RATIO);
            case "2X谐波" -> new Verdict("轴系严重不对中 (2X频带能量恶化)", evidence,
                    "复检联轴器对中偏差并调整对中垫片，检查基础沉降与管道应力。", DiagnosticMetric.FREQ_2X_AMP);
            case "BPFO外圈包络" -> new Verdict("轴承外圈点蚀损伤 (高频包络解调特征突出)", evidence,
                    "加注润滑脂并计划更换同型号轴承，采集包络谱确认 BPFO 谐波族后纳入检修计划。",
                    DiagnosticMetric.ENVELOPE_RMS);
            case "BPFI内圈包络" -> new Verdict("轴承内圈点蚀损伤 (高频包络解调特征突出)", evidence,
                    "内圈缺陷随转频调制，建议缩短监测周期并安排停机更换轴承。", DiagnosticMetric.ENVELOPE_RMS);
            case "ISO10816烈度" -> new Verdict("机械总振动超标 (ISO 10816 物理限值击穿)", evidence,
                    "振动烈度突破 ISO 10816 限值，立即排查松动、共振与基础缺陷，必要时停机。",
                    DiagnosticMetric.V_RMS);
            case "温升" -> new Verdict("轴承温升异常 (热传导补偿评分击穿)", evidence,
                    "检查润滑状态与冷却条件，复测轴承温度趋势，排查载荷异常。", DiagnosticMetric.TEMPERATURE);
            case "轴承温度" -> new Verdict("轴承温度偏高 (相对温度基线评分击穿)", evidence,
                    "检查润滑状态与冷却条件，复测轴承温度趋势，排查载荷异常。", DiagnosticMetric.TEMPERATURE);
            default -> null;
        };
    }

    private static double nvl(Double value) { return value == null ? 0.0 : value; }
}
