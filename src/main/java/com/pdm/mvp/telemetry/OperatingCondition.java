package com.pdm.mvp.telemetry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工况信息：转速、负载率（%）与稳态标志；按模态可选，用于工况门禁与基线学习筛选。
 * raw 保留协议原始工况键（如 speed_rpm/load_ratio/is_steady），供机理插件按文档键名读取。
 */
public record OperatingCondition(Double rpm, Double loadPct, Boolean steadyState, Map<String, Object> raw) {

    public OperatingCondition(Double rpm, Double loadPct, Boolean steadyState) {
        this(rpm, loadPct, steadyState, Map.of());
    }

    public OperatingCondition {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (raw != null) for (var entry : raw.entrySet()) copy.put(entry.getKey(), entry.getValue());
        raw = Collections.unmodifiableMap(copy);
    }

    /** 读取工况值：优先原始键，否则按规范键（rpm/speed_rpm/load_pct/load_ratio/steady_state/is_steady）推导。 */
    public Object get(String key) {
        if (raw.containsKey(key)) return raw.get(key);
        return switch (key == null ? "" : key) {
            case "rpm", "speed_rpm" -> rpm;
            case "load_pct" -> loadPct;
            case "load_ratio" -> loadPct == null ? null : loadPct / 100.0;
            case "steady_state", "is_steady" -> steadyState;
            default -> null;
        };
    }
}
