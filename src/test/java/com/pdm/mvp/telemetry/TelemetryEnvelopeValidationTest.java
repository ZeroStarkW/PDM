package com.pdm.mvp.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pdm.mvp.analytics.DegradationTracker;
import com.pdm.mvp.analytics.HealthIndexCalculator;
import com.pdm.mvp.analytics.RotatingVibrationPlugin;
import com.pdm.mvp.controlvalve.ControlValvePlugin;
import com.pdm.mvp.controlvalve.ControlValveProperties;
import com.pdm.mvp.mcsa.McsaPlugin;
import com.pdm.mvp.mcsa.McsaProperties;
import com.pdm.mvp.modality.ModalityPlugin;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.switchgear.CircuitBreakerPlugin;
import com.pdm.mvp.switchgear.SwitchgearProperties;
import jakarta.validation.Validation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 统一契约校验的补充规则：可选 a_peak、特征编码命名与保留字段、按方向与模态区分的时钟偏差。
 */
class TelemetryEnvelopeValidationTest {
    private static final Instant NOW = Instant.parse("2026-11-23T03:00:00Z");
    private static final long NOW_MILLIS = NOW.toEpochMilli();
    /** 设计说明书 §2.1 旋转振动载荷原样抄录（无 a_peak，指标为设计命名）。 */
    private static final String DESIGN_ROTATING_SAMPLE = """
            {
              "protocol_version": "2.1.0",
              "timestamp": 1732262400000,
              "asset_id": "PUMP_SYSTEM_01",
              "channel_id": "CH_DE_RADIAL_VIB",
              "modality_type": "ROTATING_VIB",
              "operating_condition": {
                "is_steady": true,
                "speed_rpm": 1498.5,
                "load_ratio": 0.85
              },
              "metrics": {
                "v_rms": 0.485,
                "feat_1x": 0.452,
                "feat_2x": 0.118,
                "env_bpfo": 0.015,
                "a_kurtosis": 3.02,
                "temp_bearing": 48.2,
                "temp_ambient": 22.0
              }
            }
            """;
    private static final Map<String, Double> VALVE_FEATURES = Map.of(
            "hysteresis_deadband_pct", 1.0, "ultrasonic_leak_rms", 0.04);
    private static final Map<String, Double> BREAKER_FEATURES = Map.of(
            "trip_duration_ms", 42.5, "peak_coil_current_a", 5.42);

    private final TelemetryValidator validator = new TelemetryValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    /** 设计 §2.1 旋转振动样本原样：a_peak 缺省也必须通过契约校验与工况门禁。 */
    @Test
    void designRotatingSampleWithoutPeakPassesValidationAndGate() {
        var request = new JsonMapper().readValue(DESIGN_ROTATING_SAMPLE, PhmTelemetryRequest.class);
        TelemetryEnvelope envelope = request.toEnvelope("FACT_01");
        var plugin = vibrationPlugin();
        new TelemetryValidator(Clock.fixed(Instant.ofEpochMilli(1732262400000L), ZoneOffset.UTC))
                .validate(envelope, plugin);

        assertNull(envelope.featureOrNull(VibrationContractAdapter.A_PEAK), "设计样本不含 a_peak");
        var gate = plugin.gate(envelope);
        assertTrue(gate.assessable(), "稳态、转速 1498.5rpm、v_rms 0.485 应可评估");
        assertFalse(gate.stopped());
    }

    /** 旧版 /api/v1/telemetry 与 pdm/v1 仍要求 a_peak：共享校验与 Bean Validation @NotNull 两道都拦。 */
    @Test
    void legacyVibrationRequestStillRequiresPeak() {
        var values = new TelemetryRequest.TelemetryValues(1.2, null, 3.1, 0.55, 0.21, null, null, 1500.0, null, null);
        var request = new TelemetryRequest(NOW_MILLIS, "FACT_01", "PUMP_P102A", "DE_RADIAL", 0, values, null);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> validator.validate(request));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertTrue(exception.getReason().contains("核心振动特征字段不能为空"));

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(request);
            assertTrue(violations.stream().anyMatch(v -> "telemetry.aPeak".equals(v.getPropertyPath().toString())),
                    "@NotNull 应拦下缺失的 a_peak");
        }
    }

    /** 统一契约路径 a_peak 可选；给出时仍须非负。 */
    @Test
    void envelopePeakIsOptionalButMustBeNonNegativeWhenPresent() {
        var plugin = vibrationPlugin();
        Map<String, Double> withoutPeak = new LinkedHashMap<>();
        withoutPeak.put("v_rms", 1.0);
        withoutPeak.put("a_kurtosis", 3.0);
        withoutPeak.put("freq_1x_amp", 0.5);
        withoutPeak.put("freq_2x_amp", 0.2);
        validator.validate(envelope(ModalityTypes.ROTATING_VIB, NOW_MILLIS, withoutPeak), plugin);

        Map<String, Double> negativePeak = new LinkedHashMap<>(withoutPeak);
        negativePeak.put("a_peak", -0.1);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> validator.validate(envelope(ModalityTypes.ROTATING_VIB, NOW_MILLIS, negativePeak), plugin));
        assertTrue(exception.getReason().contains("非负"));
    }

    /** 特征键即基线特征编码与时序库列名：非法命名与平台保留字段一律 400，且报错点名该键。 */
    @Test
    void featureKeysMustBeLowerSnakeCaseAndMustNotShadowPlatformFields() {
        for (String reserved : TelemetryValidator.RESERVED_FEATURE_KEYS) {
            Map<String, Double> features = new LinkedHashMap<>(VALVE_FEATURES);
            features.put(reserved, 1.0);
            ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                    () -> validator.validate(envelope(ModalityTypes.CONTROL_VALVE_PROCESS, NOW_MILLIS, features), valvePlugin()));
            assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
            assertTrue(exception.getReason().contains("保留字段") && exception.getReason().contains(reserved),
                    "保留字段 " + reserved + " 应被点名拒绝: " + exception.getReason());
        }
        for (String illegal : List.of("Trip_Duration", "1x_amp", "a-peak", "a.b", "x".repeat(65))) {
            Map<String, Double> features = new LinkedHashMap<>(VALVE_FEATURES);
            features.put(illegal, 1.0);
            ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                    () -> validator.validate(envelope(ModalityTypes.CONTROL_VALVE_PROCESS, NOW_MILLIS, features), valvePlugin()));
            assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
            assertTrue(exception.getReason().contains("非法") && exception.getReason().contains(illegal),
                    "非法键 " + illegal + " 应被点名拒绝: " + exception.getReason());
        }
        Map<String, Double> longest = new LinkedHashMap<>(VALVE_FEATURES);
        longest.put("a" + "b".repeat(63), 1.0);
        validator.validate(envelope(ModalityTypes.CONTROL_VALVE_PROCESS, NOW_MILLIS, longest), valvePlugin());
    }

    /** 全部内置插件声明的特征码（必需/基线/时序）都必须通过命名规则，且原样通过契约校验。 */
    @Test
    void everyDeclaredFeatureCodeOfEveryPluginIsValid() {
        for (ModalityPlugin plugin : List.of(vibrationPlugin(), mcsaPlugin(), breakerPlugin(Duration.ofDays(30)), valvePlugin())) {
            var codes = new LinkedHashSet<>(plugin.requiredFeatures());
            codes.addAll(plugin.baselineFeatureCodes());
            codes.addAll(plugin.seriesFeatureCodes());
            for (String code : codes) {
                assertTrue(!TelemetryValidator.RESERVED_FEATURE_KEYS.contains(code)
                        && TelemetryValidator.FEATURE_KEY.matcher(code).matches(),
                        plugin.modalityType() + " 声明了非法特征码: " + code);
            }
            var probe = new TelemetryEnvelope(ModalityTypes.SUPPORTED_CONTRACT_VERSION, plugin.modalityType(), NOW_MILLIS,
                    "FACT_01", "D", "P", 0, new OperatingCondition(1440.0, 75.0, true), codes.stream()
                            .collect(java.util.stream.Collectors.toMap(code -> code, code -> 1.0, (a, b) -> a, LinkedHashMap::new)),
                    null, null);
            validator.validate(probe, plugin);
        }
    }

    /** 过去方向的时钟偏差按模态放宽：断路器事件（max-past-delay 30 天）接受 2 小时前的补传事件，连续型模态仍只容忍 5 分钟。 */
    @Test
    void pastSkewToleranceFollowsPluginMaxPastDelay() {
        long twoHours = Duration.ofHours(2).toMillis();
        validator.validate(envelope(ModalityTypes.SWITCHGEAR_EVENT, NOW_MILLIS - twoHours, BREAKER_FEATURES),
                breakerPlugin(Duration.ofDays(30)));

        assertSkewRejected(envelope(ModalityTypes.CONTROL_VALVE_PROCESS, NOW_MILLIS - twoHours, VALVE_FEATURES), valvePlugin());
        assertSkewRejected(envelope(ModalityTypes.ROTATING_VIB, NOW_MILLIS - twoHours,
                Map.of("v_rms", 1.0, "a_kurtosis", 3.0, "freq_1x_amp", 0.5, "freq_2x_amp", 0.2)), vibrationPlugin());
        assertSkewRejected(envelope(ModalityTypes.SWITCHGEAR_EVENT, NOW_MILLIS - twoHours, BREAKER_FEATURES),
                breakerPlugin(Duration.ofMinutes(10)));
        validator.validate(envelope(ModalityTypes.SWITCHGEAR_EVENT, NOW_MILLIS - Duration.ofMinutes(9).toMillis(),
                BREAKER_FEATURES), breakerPlugin(Duration.ofMinutes(10)));
    }

    /** 未来方向的时钟偏差不因模态放宽：超前 10 分钟的断路器事件同样被拒。 */
    @Test
    void futureSkewBeyondFiveMinutesIsAlwaysRejected() {
        long tenMinutes = Duration.ofMinutes(10).toMillis();
        assertSkewRejected(envelope(ModalityTypes.SWITCHGEAR_EVENT, NOW_MILLIS + tenMinutes, BREAKER_FEATURES),
                breakerPlugin(Duration.ofDays(30)));
        assertSkewRejected(envelope(ModalityTypes.CONTROL_VALVE_PROCESS, NOW_MILLIS + tenMinutes, VALVE_FEATURES), valvePlugin());

        long fourMinutes = Duration.ofMinutes(4).toMillis();
        validator.validate(envelope(ModalityTypes.SWITCHGEAR_EVENT, NOW_MILLIS + fourMinutes, BREAKER_FEATURES),
                breakerPlugin(Duration.ofDays(30)));
        validator.validate(envelope(ModalityTypes.CONTROL_VALVE_PROCESS, NOW_MILLIS - fourMinutes, VALVE_FEATURES), valvePlugin());
        assertSkewRejected(envelope(ModalityTypes.CONTROL_VALVE_PROCESS, NOW_MILLIS - Duration.ofMinutes(6).toMillis(),
                VALVE_FEATURES), valvePlugin());
    }

    private void assertSkewRejected(TelemetryEnvelope envelope, ModalityPlugin plugin) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> validator.validate(envelope, plugin));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertTrue(exception.getReason().contains("时间戳"), exception.getReason());
    }

    private static TelemetryEnvelope envelope(String modality, long timestamp, Map<String, Double> features) {
        return new TelemetryEnvelope(ModalityTypes.SUPPORTED_CONTRACT_VERSION, modality, timestamp, "FACT_01", "DEV", "PT",
                0, null, new LinkedHashMap<>(features), null, null);
    }

    private static RotatingVibrationPlugin vibrationPlugin() {
        return new RotatingVibrationPlugin(new HealthIndexCalculator(), null,
                new DegradationTracker(Duration.ofHours(24)));
    }

    private static McsaPlugin mcsaPlugin() {
        return new McsaPlugin(new McsaProperties(false, 20, 100, 15, 54, 40), null);
    }

    private static CircuitBreakerPlugin breakerPlugin(Duration maxPastDelay) {
        return new CircuitBreakerPlugin(new SwitchgearProperties(true, 1.10, 1.20, 0.80, Duration.ofDays(30),
                Duration.ofDays(365), Duration.ofDays(30), maxPastDelay));
    }

    private static ControlValvePlugin valvePlugin() {
        return new ControlValvePlugin(new ControlValveProperties(true, 2.5, 5.0, 0.2, 0.5, Duration.ofHours(1)),
                Duration.ofHours(24));
    }
}
