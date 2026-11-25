package com.pdm.mvp.switchgear;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 高压断路器模态配置（设计说明书 §4.3）：动作型事件模态的告警开关、分闸时间分级倍率、
 * 线圈电流下限比率与稀疏采样时限（新鲜度/样本保留/劣化窗口/补传事件最大滞后）。
 */
@ConfigurationProperties("pdm.switchgear")
public record SwitchgearProperties(boolean alertsEnabled, double durationAttentionRatio, double durationDangerRatio,
        double currentAttentionRatio, Duration staleAfter, Duration sampleRetention, Duration degradationWindow,
        Duration maxPastDelay) {

    public SwitchgearProperties {
        if (!Double.isFinite(durationAttentionRatio) || !Double.isFinite(durationDangerRatio)
                || !Double.isFinite(currentAttentionRatio))
            throw new IllegalArgumentException("pdm.switchgear 倍率配置必须为有限数值");
        if (durationDangerRatio <= durationAttentionRatio || durationAttentionRatio <= 1.0)
            throw new IllegalArgumentException("pdm.switchgear 分闸时间倍率必须满足 danger > attention > 1");
        if (currentAttentionRatio <= 0 || currentAttentionRatio >= 1.0)
            throw new IllegalArgumentException("pdm.switchgear.current-attention-ratio 必须在 (0,1) 区间");
        if (staleAfter.isZero() || staleAfter.isNegative() || sampleRetention.isZero() || sampleRetention.isNegative()
                || degradationWindow.isZero() || degradationWindow.isNegative())
            throw new IllegalArgumentException("pdm.switchgear 时限配置必须为正时长");
        // maxPastDelay：断网补传事件的允许滞后；零表示不放宽（仍只容忍 5 分钟时钟偏差）
        if (maxPastDelay == null || maxPastDelay.isNegative())
            throw new IllegalArgumentException("pdm.switchgear.max-past-delay 不能为负时长");
    }
}
