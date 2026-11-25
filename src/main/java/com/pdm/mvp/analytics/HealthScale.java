package com.pdm.mvp.analytics;

/**
 * 健康评分共享曲线与分档：相对基线偏离曲线与 HI 分档供各模态插件复用。
 */
public final class HealthScale {
    private HealthScale() {}

    /** 相对基线健康分：比值 ≤1.15 满分，超出后按幂律衰减。 */
    public static double relativeScore(double current, Double base) {
        if (base == null || base <= 1e-4) return 100.0;
        double ratio = current / base;
        if (ratio <= 1.15) return 100.0;
        return 100.0 / (1.0 + Math.pow((ratio - 1.0) / 1.5, 2.2));
    }

    /**
     * “越低越差”特征（如线圈电流峰值、合闸速度）的相对评分：恶化倍率按 基线/当前 反转。
     * lowerIsWorse == false 时与 {@link #relativeScore(double, Double)} 完全一致。
     */
    public static double relativeScore(double current, Double base, boolean lowerIsWorse) {
        if (!lowerIsWorse) return relativeScore(current, base);
        if (base == null || base <= 1e-4) return 100.0;
        if (current <= 1e-4) return 0.0;
        return relativeScore(base, current);
    }

    public static String levelOf(double healthIndex) {
        if (healthIndex >= 70.0) return "HEALTHY";
        if (healthIndex >= 50.0) return "ATTENTION";
        if (healthIndex >= 30.0) return "WARNING";
        return "DANGER";
    }

    /** 分档：绝对物理限值击穿直接判 DANGER，其余按 HI 常规分档。 */
    public static String levelOf(double healthIndex, boolean hardLimitBreached) {
        return hardLimitBreached ? "DANGER" : levelOf(healthIndex);
    }

    public static int statusCodeOf(String level) {
        return switch (level) {
            case "HEALTHY" -> 0;
            case "ATTENTION" -> 1;
            case "WARNING" -> 2;
            default -> 3;
        };
    }
}
