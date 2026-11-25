package com.pdm.mvp.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pdm.mvp.analytics.RotatingVibrationPlugin;
import com.pdm.mvp.mcsa.McsaPlugin;
import com.pdm.mvp.mcsa.McsaProperties;
import com.pdm.mvp.modality.ModalityPluginRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 统一契约校验：未知模态、契约版本、缺失必需特征与非有限数值必须被拒绝。
 */
class TelemetryContractValidationTest {
    private final TelemetryValidator validator = new TelemetryValidator(fixedClock());

    @Test
    void unknownModalityIsRejectedByRegistry() {
        var registry = new ModalityPluginRegistry(List.of(vibrationPlugin()));
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> registry.require("ULTRASONIC"));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void unsupportedContractVersionIsRejected() {
        var envelope = vibrationEnvelope(java.util.Map.of("v_rms", 1.0, "a_peak", 1.0, "a_kurtosis", 3.0,
                "freq_1x_amp", 1.0, "freq_2x_amp", 0.5), 1);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> validator.validate(envelope, vibrationPlugin()));
        assertTrue(exception.getReason().contains("不支持的遥测契约版本"));
    }

    @Test
    void missingRequiredFeatureIsRejected() {
        var envelope = vibrationEnvelope(Map.of("v_rms", 1.0, "a_peak", 1.0, "a_kurtosis", 3.0,
                "freq_1x_amp", 1.0), 2);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> validator.validate(envelope, vibrationPlugin()));
        assertTrue(exception.getReason().contains("freq_2x_amp"));
    }

    @Test
    void nonFiniteFeatureValueIsRejected() {
        var envelope = vibrationEnvelope(Map.of("v_rms", 1.0, "a_peak", 1.0, "a_kurtosis", 3.0,
                "freq_1x_amp", 1.0, "freq_2x_amp", Double.NaN), 2);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> validator.validate(envelope, vibrationPlugin()));
        assertTrue(exception.getReason().contains("有限数值"));
    }

    @Test
    void negativeVibrationAmplitudeIsRejected() {
        var envelope = vibrationEnvelope(Map.of("v_rms", -1.0, "a_peak", 1.0, "a_kurtosis", 3.0,
                "freq_1x_amp", 1.0, "freq_2x_amp", 0.5), 2);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> validator.validate(envelope, vibrationPlugin()));
        assertTrue(exception.getReason().contains("非负"));
    }

    @Test
    void mcsaSlipBeyondPhysicalRangeIsRejected() {
        var envelope = mcsaEnvelope(30.0);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> validator.validate(envelope, mcsaPlugin()));
        assertTrue(exception.getReason().contains("slip_pct"));
    }

    @Test
    void validMcsaEnvelopePassesValidation() {
        validator.validate(mcsaEnvelope(2.5), mcsaPlugin());
    }

    private com.pdm.mvp.analytics.RotatingVibrationPlugin vibrationPlugin() {
        return new com.pdm.mvp.analytics.RotatingVibrationPlugin(
                new com.pdm.mvp.analytics.HealthIndexCalculator(), null,
                new com.pdm.mvp.analytics.DegradationTracker(java.time.Duration.ofHours(24)));
    }

    private McsaPlugin mcsaPlugin() {
        return new McsaPlugin(new McsaProperties(false, 20, 100, 15, 54, 40), null);
    }

    private TelemetryEnvelope vibrationEnvelope(Map<String, Double> features, int contractVersion) {
        return new TelemetryEnvelope(contractVersion, "ROTATING_VIB", fixedClock().instant().toEpochMilli(),
                "FACT_01", "PUMP_P102A", "DE_RADIAL", 0, null, new LinkedHashMap<>(features), null, null);
    }

    private TelemetryEnvelope mcsaEnvelope(double slipPct) {
        Map<String, Double> features = new LinkedHashMap<>();
        features.put("mains_freq_hz", 50.0);
        features.put("slip_pct", slipPct);
        features.put("sideband_amp_db", -93.0);
        features.put("carrier_amp_db", -33.0);
        features.put("load_pct", 75.0);
        return new TelemetryEnvelope(2, "MCSA", fixedClock().instant().toEpochMilli(),
                "FACT_01", "MOTOR_M01", "CURRENT", 0,
                new OperatingCondition(1440.0, 75.0, true), features, null, null);
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-11-23T03:00:00Z"), ZoneOffset.UTC);
    }
}
