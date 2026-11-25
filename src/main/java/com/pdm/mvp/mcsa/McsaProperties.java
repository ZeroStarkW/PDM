package com.pdm.mvp.mcsa;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCSA 试点配置。默认阈值为候选设计值（附件假设：边频带间距 54/40 dB），
 * 必须结合目标电机、负载区间与现场标注数据校准后，方可开启 alertsEnabled 启用告警。
 */
@ConfigurationProperties("pdm.mcsa")
public record McsaProperties(boolean alertsEnabled, double loadMinPct, double loadMaxPct, double slipMaxPct,
        double sidebandWarnDb, double sidebandDangerDb) {
    public McsaProperties {
        if (loadMinPct < 0 || loadMaxPct <= loadMinPct)
            throw new IllegalArgumentException("pdm.mcsa.load-min-pct/load-max-pct 必须满足 0 ≤ min < max");
        if (slipMaxPct <= 0) throw new IllegalArgumentException("pdm.mcsa.slip-max-pct 必须为正数");
        if (sidebandDangerDb >= sidebandWarnDb)
            throw new IllegalArgumentException("pdm.mcsa.sideband-danger-db 必须小于 sideband-warn-db");
    }
}
