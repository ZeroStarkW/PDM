package com.pdm.mvp.modality;

import com.pdm.mvp.asset.PointEntity;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 数据新鲜度策略（按模态）：连续型模态沿用全局 pdm.telemetry.stale-after，
 * 事件型稀疏模态（如断路器动作间隔可达数天）经插件声明更长的新鲜度时限。
 */
@Component
public class TelemetryFreshnessPolicy {
    private final ModalityPluginRegistry plugins;
    private final Duration defaultStaleAfter;

    public TelemetryFreshnessPolicy(ModalityPluginRegistry plugins,
            @Value("${pdm.telemetry.stale-after:PT5M}") Duration defaultStaleAfter) {
        if (defaultStaleAfter.isZero() || defaultStaleAfter.isNegative())
            throw new IllegalArgumentException("pdm.telemetry.stale-after must be positive");
        this.plugins = plugins;
        this.defaultStaleAfter = defaultStaleAfter;
    }

    /** 按模态标识解析新鲜度时限；未知模态返回全局默认。 */
    public Duration staleAfter(String modalityType) {
        if (modalityType == null || modalityType.isBlank()) return defaultStaleAfter;
        try {
            return plugins.require(modalityType).staleAfter(defaultStaleAfter);
        } catch (RuntimeException unknownModality) {
            return defaultStaleAfter;
        }
    }

    public Duration staleAfter(PointEntity point) { return staleAfter(point.getModalityType()); }

    /** 按模态类型解析时限的函数视图（供静态汇总工具 DeviceHealthRollup 复用）。 */
    public Function<String, Duration> byModality() { return this::staleAfter; }
}
