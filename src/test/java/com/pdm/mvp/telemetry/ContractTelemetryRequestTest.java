package com.pdm.mvp.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pdm.mvp.modality.ModalityTypes;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 统一契约工况别名：speed_rpm/load_ratio/is_steady 与规范键等价解析，原始键全量保留。 */
class ContractTelemetryRequestTest {
    private static final String ALIAS_PAYLOAD = """
            {
              "contract_version": 2,
              "modality_type": "SWITCHGEAR_EVENT",
              "timestamp": 1732262400000,
              "factory_id": "FACT_01",
              "device_code": "BREAKER_10KV_02",
              "point_code": "CH_TRIP_UNIT",
              "data_quality": 0,
              "operating_condition": {"speed_rpm": 1498.5, "load_ratio": 0.85, "is_steady": true},
              "features": {"trip_duration_ms": 42.5, "peak_coil_current_a": 5.42}
            }
            """;
    private static final String CANONICAL_PAYLOAD = """
            {
              "contract_version": 2,
              "modality_type": "MCSA",
              "timestamp": 1732262400000,
              "factory_id": "FACT_01",
              "device_code": "MOTOR_M01",
              "point_code": "STATOR",
              "data_quality": 0,
              "operating_condition": {"rpm": 1440.0, "load_pct": 75.0, "steady_state": true},
              "features": {"mains_freq_hz": 50.0, "slip_pct": 2.5, "sideband_amp_db": -93.0,
                           "carrier_amp_db": -33.0, "load_pct": 75.0}
            }
            """;

    @Test
    void aliasKeysDeriveComponentsAndPreserveRawMap() {
        ContractTelemetryRequest request = new JsonMapper().readValue(ALIAS_PAYLOAD, ContractTelemetryRequest.class);
        TelemetryEnvelope envelope = request.toEnvelope();

        var condition = envelope.condition();
        assertEquals(1498.5, condition.rpm(), 1e-9, "rpm 应由 speed_rpm 推导");
        assertEquals(85.0, condition.loadPct(), 1e-9, "load_pct 应由 load_ratio×100 推导");
        assertEquals(Boolean.TRUE, condition.steadyState(), "steadyState 应由 is_steady 推导");
        assertEquals(1498.5, (double) condition.get("speed_rpm"), 1e-9);
        assertEquals(0.85, (double) condition.get("load_ratio"), 1e-9);
        assertEquals(Boolean.TRUE, condition.get("is_steady"));
        assertFalse(condition.raw().containsKey("rpm"));
        assertFalse(condition.raw().containsKey("load_pct"));
        assertFalse(condition.raw().containsKey("steady_state"));
    }

    @Test
    void canonicalKeysKeepOriginalBehaviour() {
        ContractTelemetryRequest request = new JsonMapper().readValue(CANONICAL_PAYLOAD, ContractTelemetryRequest.class);
        TelemetryEnvelope envelope = request.toEnvelope();

        assertEquals(ModalityTypes.MCSA, envelope.modalityType());
        var condition = envelope.condition();
        assertEquals(1440.0, condition.rpm(), 1e-9);
        assertEquals(75.0, condition.loadPct(), 1e-9);
        assertEquals(Boolean.TRUE, condition.steadyState());
        assertEquals(1440.0, (double) condition.get("rpm"), 1e-9);
        assertEquals(75.0, (double) condition.get("load_pct"), 1e-9);
        assertFalse(condition.raw().containsKey("speed_rpm"), "未提供的别名键不应写入 raw 映射");
        assertFalse(condition.raw().containsKey("load_ratio"));
        assertFalse(condition.raw().containsKey("is_steady"));
        assertEquals(1440.0, (double) condition.get("speed_rpm"), 1e-9, "别名键可按规范分量推导");
        assertTrue(condition.raw().containsKey("steady_state"));
    }

    @Test
    void canonicalKeysTakePrecedenceOverAliases() {
        ContractTelemetryRequest request = new ContractTelemetryRequest(2, "MCSA", 1732262400000L, "FACT_01",
                "M", "P", 0, new ContractTelemetryRequest.OperatingConditionInput(1440.0, 75.0, true, 2980.0,
                        0.5, false),
                java.util.Map.of("slip_pct", 2.5), null, null);
        var condition = request.toEnvelope().condition();
        assertEquals(1440.0, condition.rpm(), 1e-9);
        assertEquals(75.0, condition.loadPct(), 1e-9);
        assertEquals(Boolean.TRUE, condition.steadyState());
        assertEquals(2980.0, (double) condition.get("speed_rpm"), 1e-9);
        assertEquals(0.5, (double) condition.get("load_ratio"), 1e-9);
        assertEquals(Boolean.FALSE, condition.get("is_steady"));
    }

    @Test
    void missingConditionYieldsNullCondition() {
        ContractTelemetryRequest request = new ContractTelemetryRequest(2, "MCSA", 1732262400000L, "FACT_01",
                "M", "P", 0, null, java.util.Map.of("slip_pct", 2.5), null, null);
        assertNull(request.toEnvelope().condition());
    }

    /** MCSA 必填 load_pct 而插件只读工况负载：指标缺少 load_pct 时由工况负载（别名或规范键）补入特征。 */
    @Test
    void mcsaLoadIsBridgedFromOperatingConditionWhenFeatureIsAbsent() {
        var alias = new ContractTelemetryRequest(2, "MCSA", 1732262400000L, "FACT_01", "MOTOR_M01", "STATOR", 0,
                new ContractTelemetryRequest.OperatingConditionInput(null, null, true, null, 0.75, null),
                java.util.Map.of("slip_pct", 2.5), null, null);
        assertEquals(75.0, alias.toEnvelope().feature("load_pct"), 1e-9, "load_ratio 别名应桥接为特征 load_pct");

        var canonical = new ContractTelemetryRequest(2, "MCSA", 1732262400000L, "FACT_01", "MOTOR_M01", "STATOR", 0,
                new ContractTelemetryRequest.OperatingConditionInput(1440.0, 60.0, true, null, null, null),
                java.util.Map.of("slip_pct", 2.5), null, null);
        assertEquals(60.0, canonical.toEnvelope().feature("load_pct"), 1e-9, "规范键 load_pct 应桥接为特征 load_pct");
    }

    /** 桥接只补缺：指标已给出 load_pct 时不覆盖，非 MCSA 模态不补，且不改动调用方传入的（不可变）映射。 */
    @Test
    void loadBridgeOnlyFillsTheGapForMcsa() {
        var withMetric = new ContractTelemetryRequest(2, "MCSA", 1732262400000L, "FACT_01", "MOTOR_M01", "STATOR", 0,
                new ContractTelemetryRequest.OperatingConditionInput(1440.0, 75.0, true, null, null, null),
                java.util.Map.of("slip_pct", 2.5, "load_pct", 40.0), null, null);
        assertEquals(40.0, withMetric.toEnvelope().feature("load_pct"), 1e-9, "指标值优先");

        var breaker = new ContractTelemetryRequest(2, "SWITCHGEAR_EVENT", 1732262400000L, "FACT_01", "BKR", "TRIP", 0,
                new ContractTelemetryRequest.OperatingConditionInput(null, 75.0, null, null, null, null),
                java.util.Map.of("trip_duration_ms", 42.5, "peak_coil_current_a", 5.42), null, null);
        assertFalse(breaker.toEnvelope().features().containsKey("load_pct"), "非 MCSA 模态不桥接负载");

        var noLoad = new ContractTelemetryRequest(2, "MCSA", 1732262400000L, "FACT_01", "MOTOR_M01", "STATOR", 0,
                new ContractTelemetryRequest.OperatingConditionInput(1440.0, null, true, null, null, null),
                java.util.Map.of("slip_pct", 2.5), null, null);
        assertFalse(noLoad.toEnvelope().features().containsKey("load_pct"), "工况无负载时不造数");
    }
}
