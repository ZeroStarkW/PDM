package com.pdm.mvp.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.pdm.mvp.mcsa.McsaPlugin;
import com.pdm.mvp.mcsa.McsaProperties;
import com.pdm.mvp.modality.BaselineSnapshot;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.timeseries.TelemetryStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * PHM 协议 2.1.0 慢周期特征遥测载荷（文档 §2.1）：三种模态共用信封、
 * 别名工况解析、metrics 有限数值过滤、版本判定，以及旋转振动设计命名指标 → 平台特征码映射（与 2.0.0 对齐）
 * 与 MCSA 负载桥接。
 */
class PhmTelemetryRequestTest {
    private static final String BREAKER_PAYLOAD = """
            {
              "protocol_version": "2.1.0",
              "timestamp": 1732262400000,
              "asset_id": "BREAKER_10KV_02",
              "channel_id": "CH_TRIP_UNIT",
              "modality_type": "SWITCHGEAR_EVENT",
              "data_quality": 0,
              "operating_condition": {"is_steady": true, "bus_voltage_kv": 10.2, "ambient_temp": 24.5},
              "metrics": {
                "trip_duration_ms": 42.5,
                "peak_coil_current_a": 5.42,
                "closing_velocity_mps": 2.15,
                "uhf_pd_count": 0,
                "contact_wear_ratio": 0.12,
                "annotation": "待现场复核"
              }
            }
            """;
    private static final String VALVE_PAYLOAD = """
            {
              "protocol_version": "2.1.0",
              "timestamp": 1732262400000,
              "asset_id": "FEED_VALVE_FCV101",
              "channel_id": "CH_VALVE_LOOP",
              "modality_type": "CONTROL_VALVE_PROCESS",
              "operating_condition": {"is_steady": true, "inlet_pressure_bar": 16.5, "medium_temp_c": 135.0},
              "metrics": {
                "hysteresis_deadband_pct": 2.15,
                "travel_time_lag_ms": 420.0,
                "ultrasonic_leak_rms": 0.04,
                "friction_force_kn": 1.25
              }
            }
            """;
    private static final String ROTATING_PAYLOAD = """
            {
              "protocol_version": "2.1.0",
              "timestamp": 1732262400000,
              "asset_id": "PUMP_SYSTEM_01",
              "channel_id": "CH_DE_RADIAL_VIB",
              "modality_type": "ROTATING_VIB",
              "operating_condition": {"is_steady": true, "speed_rpm": 1498.5, "load_ratio": 0.85},
              "metrics": {"v_rms": 0.485, "feat_1x": 0.452, "feat_2x": 0.118, "env_bpfo": 0.015,
                          "a_kurtosis": 3.02, "temp_bearing": 48.2, "temp_ambient": 22.0}
            }
            """;
    private static final String MCSA_ALIAS_PAYLOAD = """
            {
              "protocol_version": "2.1.0",
              "timestamp": 1732262400000,
              "asset_id": "MOTOR_M01",
              "channel_id": "CH_STATOR_CURRENT",
              "modality_type": "ELECTRICAL_MCSA",
              "metrics": {"mains_freq_hz": 50.0, "slip_pct": 2.5, "sideband_amp_db": -93.0, "carrier_amp_db": -33.0}
            }
            """;

    @Test
    void parsesBreakerVariantWithConditionRawPreservationAndFiniteFiltering() {
        PhmTelemetryRequest request = new JsonMapper().readValue(BREAKER_PAYLOAD, PhmTelemetryRequest.class);
        assertTrue(request.isSupportedProtocol());
        TelemetryEnvelope envelope = request.toEnvelope("FACT_01");

        assertEquals(ModalityTypes.SUPPORTED_CONTRACT_VERSION, envelope.contractVersion());
        assertEquals(ModalityTypes.SWITCHGEAR_EVENT, envelope.modalityType());
        assertEquals("BREAKER_10KV_02", envelope.deviceCode());
        assertEquals("CH_TRIP_UNIT", envelope.pointCode());
        assertEquals(0, envelope.dataQuality(), "缺省 data_quality 应为 0");
        assertEquals(42.5, envelope.feature("trip_duration_ms"), 1e-9);
        assertEquals(5.42, envelope.feature("peak_coil_current_a"), 1e-9);
        assertEquals(2.15, envelope.feature("closing_velocity_mps"), 1e-9);
        assertEquals(0.0, envelope.feature("uhf_pd_count"), 1e-9);
        assertEquals(0.12, envelope.feature("contact_wear_ratio"), 1e-9);
        assertNull(envelope.featureOrNull("annotation"), "非数值 metric 应被静默丢弃");
        assertEquals(Boolean.TRUE, envelope.condition().steadyState());
        assertEquals(10.2, (double) envelope.condition().get("bus_voltage_kv"), 1e-9, "工况原始键必须全量保留");
        assertEquals(24.5, (double) envelope.condition().get("ambient_temp"), 1e-9);
    }

    @Test
    void parsesValveVariant() {
        PhmTelemetryRequest request = new JsonMapper().readValue(VALVE_PAYLOAD, PhmTelemetryRequest.class);
        TelemetryEnvelope envelope = request.toEnvelope("FACT_01");

        assertEquals(ModalityTypes.CONTROL_VALVE_PROCESS, envelope.modalityType());
        assertEquals("FEED_VALVE_FCV101", envelope.deviceCode());
        assertEquals("CH_VALVE_LOOP", envelope.pointCode());
        assertEquals(2.15, envelope.feature("hysteresis_deadband_pct"), 1e-9);
        assertEquals(420.0, envelope.feature("travel_time_lag_ms"), 1e-9);
        assertEquals(1.25, envelope.feature("friction_force_kn"), 1e-9);
        assertEquals(Boolean.TRUE, envelope.condition().steadyState());
    }

    /** 文档 §2.1 旋转振动指标为设计命名（feat_1x / env_bpfo / temp_bearing…），须映射为平台特征码后进入统一契约。 */
    @Test
    void parsesRotatingVariantWithAliasConditionDerivation() {
        PhmTelemetryRequest request = new JsonMapper().readValue(ROTATING_PAYLOAD, PhmTelemetryRequest.class);
        TelemetryEnvelope envelope = request.toEnvelope("FACT_01");

        assertEquals(ModalityTypes.ROTATING_VIB, envelope.modalityType());
        assertEquals("PUMP_SYSTEM_01", envelope.deviceCode());
        assertEquals("CH_DE_RADIAL_VIB", envelope.pointCode());
        assertEquals(1498.5, envelope.condition().rpm(), 1e-9, "rpm 应由 speed_rpm 别名推导");
        assertEquals(85.0, envelope.condition().loadPct(), 1e-9, "load_pct 应由 load_ratio×100 推导");
        assertEquals(Boolean.TRUE, envelope.condition().steadyState());
        assertEquals(0.85, (double) envelope.condition().get("load_ratio"), 1e-9);
        assertEquals(0.485, envelope.feature("v_rms"), 1e-9);
        assertEquals(0.452, envelope.feature(VibrationContractAdapter.FREQ_1X_AMP), 1e-9, "feat_1x → freq_1x_amp");
        assertEquals(0.118, envelope.feature(VibrationContractAdapter.FREQ_2X_AMP), 1e-9, "feat_2x → freq_2x_amp");
        assertEquals(0.015, envelope.feature(VibrationContractAdapter.ENVELOPE_BPFO_AMP), 1e-9, "env_bpfo → envelope_bpfo_amp");
        assertNull(envelope.featureOrNull("env_bpfo"), "设计命名指标映射后不再以别名键出现");
        assertEquals(48.2, envelope.feature(VibrationContractAdapter.TEMPERATURE), 1e-9, "temp_bearing → temperature");
        assertEquals(22.0, envelope.feature(VibrationContractAdapter.TEMP_AMBIENT), 1e-9);
        assertEquals(26.2, envelope.feature(VibrationContractAdapter.TEMP_RISE), 1e-9, "温升 = temp_bearing − temp_ambient");
        assertEquals(1498.5, envelope.feature(VibrationContractAdapter.RPM), 1e-9, "指标未给转速时取工况 speed_rpm 桥接为 rpm");
    }

    /** 契约对齐：同一组文档命名指标经 2.0.0 记录与 2.1.0 适配器必须得到完全相同的特征映射。 */
    @Test
    void rotatingAliasMappingIsIdenticalToProtocol20RecordForTheSameDocNamedMetrics() {
        String metrics = """
                "temp_bearing": 48.2, "temp_ambient": 22.0, "v_rms": 0.485, "a_peak": 1.25, "a_kurtosis": 3.02,
                "crest_factor": 2.58, "feat_1x": 0.452, "feat_2x": 0.118, "feat_3x": 0.045, "feat_sub": 0.012,
                "env_rms": 0.155, "env_bpfo": 0.015, "env_bpfi": 0.012, "sei_index": 0.08
                """;
        for (String condition : new String[]{"", ",\"operating_condition\":{\"rpm\":1498.5,\"is_steady\":true}"}) {
            String protocol21Condition = condition.isEmpty() ? ""
                    : ",\"operating_condition\":{\"speed_rpm\":1498.5,\"is_steady\":true}";
            String protocol20 = "{\"protocol_version\":\"2.0.0\",\"timestamp\":1732262400000,\"asset_id\":\"PUMP_SYSTEM_01\","
                    + "\"point_id\":\"PUMP01_MTR_DE_RADIAL\",\"data_quality\":0" + condition + ",\"metrics\":{" + metrics + "}}";
            String protocol21 = "{\"protocol_version\":\"2.1.0\",\"timestamp\":1732262400000,\"asset_id\":\"PUMP_SYSTEM_01\","
                    + "\"channel_id\":\"PUMP01_MTR_DE_RADIAL\",\"modality_type\":\"ROTATING_VIB\"" + protocol21Condition
                    + ",\"metrics\":{" + metrics + "}}";
            var mapper = new JsonMapper();
            TelemetryEnvelope from20 = mapper.readValue(protocol20, ProtocolTelemetryRequest.class).toEnvelope("FACT_01");
            TelemetryEnvelope from21 = mapper.readValue(protocol21, PhmTelemetryRequest.class).toEnvelope("FACT_01");

            assertEquals(from20.features(), from21.features(), "2.0.0 与 2.1.0 的旋转振动特征映射必须一致");
            var expected = new java.util.HashSet<>(VibrationContractAdapter.KNOWN_CODES);
            if (condition.isEmpty()) expected.remove(VibrationContractAdapter.RPM);
            assertEquals(expected, from21.features().keySet(),
                    "14 个文档命名指标全部映射为已知振动码（含派生 temp_rise；工况带转速时另有桥接的 rpm）");
            assertFalse(from21.features().containsKey("temp_bearing"), "设计命名键不得残留");
        }
    }

    /** 平台特征码已给出时别名不得覆盖；旋转振动仅保留已知振动码，其余指标键丢弃。 */
    @Test
    void platformFeatureCodeWinsOverDocumentAliasAndUnknownMetricsAreDropped() {
        String payload = """
                {"protocol_version": "2.1.0", "timestamp": 1732262400000, "asset_id": "PUMP_SYSTEM_01",
                 "channel_id": "CH_DE_RADIAL_VIB", "modality_type": "ROTATING_VIB",
                 "metrics": {"feat_1x": 9.9, "freq_1x_amp": 0.452, "some_vendor_metric": 7.0}}
                """;
        var features = new JsonMapper().readValue(payload, PhmTelemetryRequest.class).toEnvelope("FACT_01").features();

        assertEquals(0.452, features.get(VibrationContractAdapter.FREQ_1X_AMP), 1e-9, "平台码已给出时以平台码为准");
        assertFalse(features.containsKey("feat_1x"), "别名键不得残留");
        assertFalse(features.containsKey("some_vendor_metric"), "非振动码不进入旋转振动特征集合");
    }

    /** MCSA 必填 load_pct 而插件只读工况负载：仅有 operating_condition.load_ratio 的 2.1.0 载荷必须通过校验且可评估。 */
    @Test
    void mcsaLoadRatioFromOperatingConditionSatisfiesRequiredLoadPct() {
        String payload = """
                {"protocol_version": "2.1.0", "timestamp": 1732262400000, "asset_id": "MOTOR_M01",
                 "channel_id": "CH_STATOR_CURRENT", "modality_type": "ELECTRICAL_MCSA",
                 "operating_condition": {"is_steady": true, "load_ratio": 0.75},
                 "metrics": {"mains_freq_hz": 50.0, "slip_pct": 2.5, "sideband_amp_db": -93.0, "carrier_amp_db": -33.0}}
                """;
        TelemetryEnvelope envelope = new JsonMapper().readValue(payload, PhmTelemetryRequest.class).toEnvelope("FACT_01");
        assertEquals(75.0, envelope.feature("load_pct"), 1e-9, "指标缺少 load_pct 时由 load_ratio×100 补入");

        var plugin = new McsaPlugin(new McsaProperties(false, 20, 100, 15, 54, 40), null);
        new TelemetryValidator(Clock.fixed(Instant.ofEpochMilli(1732262400000L), ZoneOffset.UTC)).validate(envelope, plugin);
        assertTrue(plugin.gate(envelope).assessable(), "负载 75% 处于 20–100% 区间且稳态，应可评估");
        var baseline = new BaselineSnapshot(1L, ModalityTypes.MCSA, "ACTIVE", 200, Instant.now(), null,
                Map.of("sideband_ratio", 0.001), Map.of());
        assertEquals(100.0, plugin.score(null, envelope, baseline, null).healthIndex(), 1e-9);
    }

    @Test
    void mcsaMetricLoadPctIsNeverOverriddenByCondition() {
        PhmTelemetryRequest request = new PhmTelemetryRequest("2.1.0", 1732262400000L, "MOTOR_M01", "CH_STATOR_CURRENT",
                "ELECTRICAL_MCSA", 0, Map.of("load_ratio", 0.9), Map.of("load_pct", 60.0), null, null);
        assertEquals(60.0, request.toEnvelope("FACT_01").feature("load_pct"), 1e-9, "指标已给出 load_pct 时不覆盖");
    }

    @Test
    void normalizesElectricalMcsaAlias() {
        PhmTelemetryRequest request = new JsonMapper().readValue(MCSA_ALIAS_PAYLOAD, PhmTelemetryRequest.class);
        TelemetryEnvelope envelope = request.toEnvelope("FACT_01");
        assertEquals(ModalityTypes.MCSA, envelope.modalityType());
    }

    @Test
    void rejectsUnsupportedProtocolVersionWith400() {
        var service = new TelemetryService(mock(com.pdm.mvp.asset.AssetLookup.class),
                mock(TelemetryValidator.class), mock(TelemetryStore.class),
                mock(com.pdm.mvp.analytics.AnalyticsEngine.class), mock(com.pdm.mvp.asset.PointRepository.class),
                mock(com.pdm.mvp.alert.AlertService.class), mock(com.pdm.mvp.modality.ModalityPluginRegistry.class));
        PhmTelemetryRequest request = new PhmTelemetryRequest("2.0.0", 1732262400000L, "A", "C",
                "SWITCHGEAR_EVENT", 0, null, null, null, null);
        assertFalse(request.isSupportedProtocol());
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.accept(request, "REST"));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertTrue(exception.getReason().contains("不支持的协议版本"));
    }

    @Test
    void snapshotUrlFallsBackAsRawSnapshotRef() {
        PhmTelemetryRequest request = new PhmTelemetryRequest("2.1.0", 1732262400000L, "A", "C",
                "SWITCHGEAR_EVENT", 0, null, null, null, "s3://phm-vault/a.bin");
        assertEquals("s3://phm-vault/a.bin", request.toEnvelope("FACT_01").rawSnapshotRef());
    }
}
