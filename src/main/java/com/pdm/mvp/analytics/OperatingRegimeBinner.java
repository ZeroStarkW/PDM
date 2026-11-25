package com.pdm.mvp.analytics;

import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * 确定性工况分箱器：把同一样本按运行工况（转速段 / 负载段）映射到稳定的 {@code bin_index}，
 * 使变频泵/风机等设备在各转速段独立学习、独立锁定、对口评分，互不污染。
 *
 * <p>兼容红线：额定转速 ±5%（或未上报转速）固定落入 {@code bin_index = 0}，
 * 与原 BaselineService 的 ±5% 学习门禁完全重合；未开启分箱的模态默认 {@code bin_index = 0}。
 */
public final class OperatingRegimeBinner {
    /** 额定/默认工况箱。 */
    public static final short DEFAULT_BIN = 0;
    /** 未填额定转速的纯变频设备按 150 RPM 步长分箱的起始箱号。 */
    static final int ABSOLUTE_RPM_BIN_BASE = 100;
    static final double ABSOLUTE_RPM_STEP = 150.0;

    private OperatingRegimeBinner() {}

    /** 旋转振动样本的工况转速（优先工况上报，回落特征 rpm；旧载荷无转速时返回 null → bin 0）。 */
    public static Double vibrationRpm(TelemetryEnvelope sample) {
        if (sample == null) return null;
        Double rpm = sample.condition() != null ? sample.condition().rpm() : null;
        return rpm != null ? rpm : sample.featureOrNull("rpm");
    }

    /** 样本的负载率（优先工况上报，回落特征 load_pct）。 */
    public static Double loadPct(TelemetryEnvelope sample) {
        if (sample == null) return null;
        Double load = sample.condition() != null ? sample.condition().loadPct() : null;
        return load != null ? load : sample.featureOrNull("load_pct");
    }

    /** 旋转振动转速分箱（从样本提取转速）。 */
    public static short resolveVibrationBin(PointEntity point, TelemetryEnvelope sample) {
        return resolveVibrationBin(point, vibrationRpm(sample));
    }

    /**
     * 旋转振动转速分箱：
     * 无有效转速 → 0（兼容无转速计旧载荷）；测点配置 rpm_bin_width ≥ 50 时按固定 RPM 带宽分箱
     * （额定 ±5% 仍优先归 0）；否则按额定转速比例分档，额定 [0.95, 1.05] → 0 与原门禁重合；
     * 未填额定转速的纯变频设备按 150 RPM 步长分入 100+ 号箱。
     */
    public static short resolveVibrationBin(PointEntity point, Double rpm) {
        if (rpm == null || !Double.isFinite(rpm) || rpm <= 0) return DEFAULT_BIN;
        double nominalRpm = nominalRpm(point);
        Double binWidth = rpmBinWidth(point);
        if (binWidth != null) {
            if (nominalRpm > 0 && Math.abs(rpm - nominalRpm) / nominalRpm <= 0.05) return DEFAULT_BIN;
            return (short) Math.clamp((int) Math.floor(rpm / binWidth) + 1, 1, 200);
        }
        if (nominalRpm > 0) {
            double ratio = rpm / nominalRpm;
            if (ratio >= 0.95 && ratio <= 1.05) return DEFAULT_BIN;
            if (ratio > 1.05) return (short) (ratio <= 1.15 ? 7 : (ratio <= 1.25 ? 8 : 9));
            if (ratio >= 0.85) return 1;
            if (ratio >= 0.75) return 2;
            if (ratio >= 0.65) return 3;
            if (ratio >= 0.55) return 4;
            if (ratio >= 0.45) return 5;
            return 6;
        }
        return (short) Math.clamp(ABSOLUTE_RPM_BIN_BASE + (int) Math.floor(rpm / ABSOLUTE_RPM_STEP),
                ABSOLUTE_RPM_BIN_BASE, 300);
    }

    /**
     * 负载率等分分箱（MCSA / 调节阀等连续模态的可选扩展）：仅当测点 metadata_config 配置了
     * load_bins（2..10）且样本带有效负载率时生效；未配置（或 load_bins ≤ 1）一律返回 0，
     * 保证演示种子与既有单模态池行为零影响。
     */
    public static short resolveLoadBin(PointEntity point, TelemetryEnvelope sample) {
        int bins = loadBins(point);
        if (bins < 2) return DEFAULT_BIN;
        Double load = loadPct(sample);
        if (load == null || !Double.isFinite(load)) return DEFAULT_BIN;
        double clamped = Math.clamp(load, 0.0, 99.999);
        return (short) (1 + Math.clamp((int) Math.floor(clamped / (100.0 / bins)), 0, bins - 1));
    }

    /** load_bins 配置解析：非法/缺失/非 1..10 整数一律视为未启用（返回 1）。 */
    public static int loadBins(PointEntity point) {
        Object value = metadata(point).get("load_bins");
        if (!(value instanceof Number n)) return 1;
        int bins = n.intValue();
        return bins >= 1 && bins <= 10 && Double.compare(n.doubleValue(), bins) == 0 ? bins : 1;
    }

    /** 测点配置的自定义转速箱宽（RPM）；未配置或小于 50 返回 null（走默认比例分箱）。 */
    public static Double rpmBinWidth(PointEntity point) {
        Object value = metadata(point).get("rpm_bin_width");
        if (value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() >= 50.0)
            return n.doubleValue();
        return null;
    }

    /** 设备额定转速；未登记（null / ≤0）视为纯变频设备。 */
    public static double nominalRpm(PointEntity point) {
        if (point == null) return 0.0;
        DeviceEntity device = point.getDevice();
        if (device == null) return 0.0;
        BigDecimal rpm = device.getRpmNominal();
        return rpm == null ? 0.0 : rpm.doubleValue();
    }

    /** 工况箱的可读标签（界面展示）：bin 0 = 额定 / 默认稳态工况；其余按转速段或负载段命名。 */
    public static String regimeLabel(PointEntity point, String modalityType, short binIndex) {
        boolean vibration = ModalityTypes.ROTATING_VIB.equals(modalityType);
        if (binIndex == DEFAULT_BIN) return vibration ? "额定转速段 (95%~105%)" : "默认稳态工况";
        if (vibration) {
            if (binIndex >= ABSOLUTE_RPM_BIN_BASE) {
                long start = (binIndex - ABSOLUTE_RPM_BIN_BASE) * (long) ABSOLUTE_RPM_STEP;
                return start + "~" + (start + (long) ABSOLUTE_RPM_STEP) + " RPM 转速段";
            }
            Double width = rpmBinWidth(point);
            if (width != null) {
                long w = Math.round(width);
                return ((binIndex - 1) * w) + "~" + (binIndex * w) + " RPM 转速段";
            }
            return switch (binIndex) {
                case 1 -> "85%~95% 转速段";
                case 2 -> "75%~85% 转速段";
                case 3 -> "65%~75% 转速段";
                case 4 -> "55%~65% 转速段";
                case 5 -> "45%~55% 转速段";
                case 6 -> "<45% 低速段";
                case 7 -> "105%~115% 超频段";
                case 8 -> "115%~125% 超频段";
                case 9 -> ">125% 高速段";
                default -> "转速箱 " + binIndex;
            };
        }
        int bins = loadBins(point);
        if (bins >= 2 && binIndex >= 1 && binIndex <= bins) {
            double step = 100.0 / bins;
            return formatPct((binIndex - 1) * step) + "~" + formatPct(binIndex * step) + "% 负载段";
        }
        return "工况箱 " + binIndex;
    }

    /** metadata_config 空安全视图（含 Mockito mock 未桩化返回 null 的场景）。 */
    private static Map<String, Object> metadata(PointEntity point) {
        if (point == null) return Map.of();
        Map<String, Object> config = point.metadataConfig();
        return config == null ? Map.of() : config;
    }

    private static String formatPct(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
