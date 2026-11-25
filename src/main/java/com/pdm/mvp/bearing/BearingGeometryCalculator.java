package com.pdm.mvp.bearing;

/**
 * 轴承特征频率几何解算库（文档 §4.1）：
 * BPFO=(n/2)·f_r·(1-νcosα)、BPFI=(n/2)·f_r·(1+νcosα)、
 * BSF=(D/2d)·f_r·(1-(νcosα)²)、FTF=0.5·f_r·(1-νcosα)，ν=d/D。
 * 系数为相对转频的倍率（×f_r），种子库与前端理论值共用本实现。
 */
public final class BearingGeometryCalculator {
    private BearingGeometryCalculator() {}

    public record BearingFactors(double bpfoFactor, double bpfiFactor, double bsfFactor, double ftfFactor) {}

    public static BearingFactors factors(int ballCount, double ballDiameter, double pitchDiameter,
            double contactAngleDegrees) {
        if (ballCount <= 0 || ballDiameter <= 0 || pitchDiameter <= 0 || ballDiameter >= pitchDiameter)
            throw new IllegalArgumentException("轴承几何参数无效");
        double ratio = ballDiameter / pitchDiameter;
        double cos = Math.cos(Math.toRadians(contactAngleDegrees));
        double nuCos = ratio * cos;
        double bpfo = (ballCount / 2.0) * (1.0 - nuCos);
        double bpfi = (ballCount / 2.0) * (1.0 + nuCos);
        double bsf = (pitchDiameter / (2.0 * ballDiameter)) * (1.0 - nuCos * nuCos);
        double ftf = 0.5 * (1.0 - nuCos);
        return new BearingFactors(bpfo, bpfi, bsf, ftf);
    }

    public static BearingFactors factors(BearingLibraryEntity bearing) {
        // 种子库预计算四系数齐全时直接读取；仅缺列时回退按几何公式现算
        if (bearing.getBpfoFactor() != null && bearing.getBpfiFactor() != null
                && bearing.getBsfFactor() != null && bearing.getFtfFactor() != null)
            return new BearingFactors(bearing.getBpfoFactor().doubleValue(), bearing.getBpfiFactor().doubleValue(),
                    bearing.getBsfFactor().doubleValue(), bearing.getFtfFactor().doubleValue());
        return factors(bearing.getBallCount(), bearing.getBallDiameter().doubleValue(),
                bearing.getPitchDiameter().doubleValue(), bearing.getContactAngleDeg().doubleValue());
    }

    /** 给定转频解算四类特征频率绝对值（Hz）。 */
    public record FaultFrequencies(double bpfoHz, double bpfiHz, double bsfHz, double ftfHz) {}

    public static FaultFrequencies frequencies(BearingFactors factors, double rotationHz) {
        return new FaultFrequencies(factors.bpfoFactor() * rotationHz, factors.bpfiFactor() * rotationHz,
                factors.bsfFactor() * rotationHz, factors.ftfFactor() * rotationHz);
    }
}
