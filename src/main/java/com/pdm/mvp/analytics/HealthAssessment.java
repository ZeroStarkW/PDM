package com.pdm.mvp.analytics;

import com.pdm.mvp.diagnostics.DiagnosticMatch;
import java.util.List;

/**
 * 健康评估结果：相对分/绝对分、综合 HI（已乘动力学抑制乘子）、分档与诊断，
 * 另附 dk/dt 斜率、η_dyn、各机理特征贡献明细与绝对物理限值击穿标识
 * （供告警根因佐证、前端展示与告警直通判定）。
 */
public record HealthAssessment(double hiRelative, double hiAbsolute, double healthIndex,
        String level, int statusCode, DiagnosticMatch diagnosis,
        double slopeDkDt, double etaDyn, List<FeatureContribution> contributions, boolean hardLimitBreached) {

    public HealthAssessment(double hiRelative, double hiAbsolute, double healthIndex,
            String level, int statusCode, DiagnosticMatch diagnosis) {
        this(hiRelative, hiAbsolute, healthIndex, level, statusCode, diagnosis, 0.0, 1.0, List.of(), false);
    }

    public HealthAssessment(double hiRelative, double hiAbsolute, double healthIndex,
            String level, int statusCode, DiagnosticMatch diagnosis, double slopeDkDt, double etaDyn,
            List<FeatureContribution> contributions) {
        this(hiRelative, hiAbsolute, healthIndex, level, statusCode, diagnosis, slopeDkDt, etaDyn,
                contributions, false);
    }

    public HealthAssessment {
        contributions = contributions == null ? List.of() : List.copyOf(contributions);
    }

    /** 单机理特征贡献：当前值/基线/倍率与得分。 */
    public record FeatureContribution(String feature, Double current, Double base, Double ratio, double score) {}
}
