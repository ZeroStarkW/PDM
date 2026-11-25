package com.pdm.mvp.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pdm.mvp.modality.ModalityTypes;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** PHM 协议 2.0.0 载荷：版本判定、JSON 反序列化与文档命名 → 平台特征码映射。 */
class ProtocolTelemetryRequestTest {
    private static final String PAYLOAD = """
            {
              "protocol_version": "2.0.0",
              "timestamp": 1732262400000,
              "asset_id": "PUMP_SYSTEM_01",
              "point_id": "PUMP01_MTR_DE_RADIAL",
              "data_quality": 0,
              "operating_condition": {"rpm": 1498.5, "load_current": 45.2, "is_steady": true},
              "metrics": {
                "temp_bearing": 48.2,
                "temp_ambient": 22.0,
                "v_rms": 0.485,
                "a_peak": 1.25,
                "a_kurtosis": 3.02,
                "crest_factor": 2.58,
                "feat_1x": 0.452,
                "feat_2x": 0.118,
                "feat_3x": 0.045,
                "feat_sub": 0.012,
                "env_rms": 0.155,
                "env_bpfo": 0.015,
                "env_bpfi": 0.012,
                "sei_index": 0.08
              }
            }
            """;

    @Test
    void parsesDocumentedPayloadAndMapsFeatureCodes() {
        ProtocolTelemetryRequest request = new JsonMapper().readValue(PAYLOAD, ProtocolTelemetryRequest.class);

        assertTrue(request.isSupportedProtocol());
        TelemetryEnvelope envelope = request.toEnvelope("FACT_01");

        assertEquals(ModalityTypes.SUPPORTED_CONTRACT_VERSION, envelope.contractVersion());
        assertEquals(ModalityTypes.ROTATING_VIB, envelope.modalityType());
        assertEquals("PUMP_SYSTEM_01", envelope.deviceCode());
        assertEquals("PUMP01_MTR_DE_RADIAL", envelope.pointCode());
        assertEquals(1498.5, envelope.condition().rpm(), 1e-9);
        assertEquals(0.452, envelope.feature(VibrationContractAdapter.FREQ_1X_AMP), 1e-9);
        assertEquals(0.118, envelope.feature(VibrationContractAdapter.FREQ_2X_AMP), 1e-9);
        assertEquals(0.045, envelope.feature(VibrationContractAdapter.FREQ_3X_AMP), 1e-9);
        assertEquals(0.012, envelope.feature(VibrationContractAdapter.FREQ_SUB_AMP), 1e-9);
        assertEquals(0.015, envelope.feature(VibrationContractAdapter.ENVELOPE_BPFO_AMP), 1e-9);
        assertEquals(0.012, envelope.feature(VibrationContractAdapter.ENVELOPE_BPFI_AMP), 1e-9);
        assertEquals(0.08, envelope.feature(VibrationContractAdapter.SEI_INDEX), 1e-9);
        assertEquals(48.2, envelope.feature(VibrationContractAdapter.TEMPERATURE), 1e-9);
        assertEquals(22.0, envelope.feature(VibrationContractAdapter.TEMP_AMBIENT), 1e-9);
        assertEquals(26.2, envelope.feature(VibrationContractAdapter.TEMP_RISE), 1e-9, "温升应由轴承−环境温度派生");
        assertEquals(1498.5, envelope.feature(VibrationContractAdapter.RPM), 1e-9, "工况 rpm 应桥接为特征 rpm，使振动行保留 RPM");
    }

    @Test
    void rpmIsBridgedOnlyWhenOperatingConditionCarriesIt() {
        ProtocolTelemetryRequest request = new ProtocolTelemetryRequest("2.0.0", 1732262400000L, "PUMP_SYSTEM_01",
                "PUMP01_MTR_DE_RADIAL", 0, null, new ProtocolTelemetryRequest.Metrics(null, null, 0.485, 1.25, 3.02,
                        null, 0.452, 0.118, null, null, null, null, null, null));
        assertFalse(request.toEnvelope("FACT_01").features().containsKey(VibrationContractAdapter.RPM), "无工况转速时不造 rpm");
    }

    @Test
    void rejectsUnsupportedProtocolVersion() {
        ProtocolTelemetryRequest request = new ProtocolTelemetryRequest("1.0.0", 1L, "A", "P", 0,
                null, new ProtocolTelemetryRequest.Metrics(null, null, 1.0, 1.0, 3.0, null, 1.0, 1.0,
                null, null, null, null, null, null));
        assertFalse(request.isSupportedProtocol());
    }
}
