package com.pdm.mvp.controlvalve;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工艺控制调节阀模态配置（设计说明书 §4.3）：告警开关、回环死区分级恶化倍率、
 * 超声泄漏绝对阈值与基线学习窗口（0.1~1Hz 低频采样，200 个样本最长约需 1 小时）。
 */
@ConfigurationProperties("pdm.control-valve")
public record ControlValveProperties(boolean alertsEnabled, double deadbandAttentionRatio, double deadbandDangerRatio,
        double leakAttentionRms, double leakDangerRms, Duration learningWindow) {

    public ControlValveProperties {
        if (!Double.isFinite(deadbandAttentionRatio) || !Double.isFinite(deadbandDangerRatio)
                || !Double.isFinite(leakAttentionRms) || !Double.isFinite(leakDangerRms))
            throw new IllegalArgumentException("pdm.control-valve 阈值配置必须为有限数值");
        if (deadbandDangerRatio <= deadbandAttentionRatio || deadbandAttentionRatio <= 1.0)
            throw new IllegalArgumentException("pdm.control-valve 死区倍率必须满足 danger > attention > 1");
        if (leakAttentionRms <= 0 || leakDangerRms <= leakAttentionRms)
            throw new IllegalArgumentException("pdm.control-valve 泄漏阈值必须满足 0 < attention < danger");
        if (learningWindow.isZero() || learningWindow.isNegative())
            throw new IllegalArgumentException("pdm.control-valve.learning-window 必须为正时长");
    }
}
