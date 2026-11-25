package com.pdm.mvp.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pdm.mvp.analytics.RotatingVibrationPlugin;
import com.pdm.mvp.analytics.SpikeFilter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 旧振动载荷 ↔ 统一契约适配回归：模拟器与旧遥测样例无需修改，行为保持等价。
 */
class VibrationContractAdapterTest {
    private final RotatingVibrationPlugin plugin =
            new RotatingVibrationPlugin(new com.pdm.mvp.analytics.HealthIndexCalculator(), null,
            new com.pdm.mvp.analytics.DegradationTracker(java.time.Duration.ofHours(24)));
    private final SpikeFilter spikeFilter = new SpikeFilter();

    @Test
    void legacyRequestMapsToRotatingVibEnvelopeWithSameFeatureValues() {
        TelemetryRequest request = request(1.2, 0.9, 3.1, 0.55, 0.21);
        TelemetryEnvelope envelope = VibrationContractAdapter.toEnvelope(request);

        assertEquals("ROTATING_VIB", envelope.modalityType());
        assertEquals(2, envelope.contractVersion());
        assertEquals(1.2, envelope.feature("v_rms"));
        assertEquals(0.9, envelope.feature("a_peak"));
        assertEquals(3.1, envelope.feature("a_kurtosis"));
        assertEquals(0.55, envelope.feature("freq_1x_amp"));
        assertEquals(0.21, envelope.feature("freq_2x_amp"));
        assertEquals(1500.0, envelope.condition().rpm());
        assertEquals(0, envelope.dataQuality());
    }

    @Test
    void envelopeRoundTripsBackToLegacyRequest() {
        TelemetryRequest request = request(1.2, 0.9, 3.1, 0.55, 0.21);
        TelemetryEnvelope envelope = VibrationContractAdapter.toEnvelope(request);
        TelemetryRequest rebuilt = VibrationContractAdapter.toRequest(envelope);

        assertEquals(request.telemetry().vRms(), rebuilt.telemetry().vRms());
        assertEquals(request.telemetry().freq2xAmp(), rebuilt.telemetry().freq2xAmp());
        assertEquals(request.factoryId(), rebuilt.factoryId());
    }

    @Test
    void stoppedGateMatchesLegacyHeuristic() {
        var lowRms = new TelemetryEnvelope(2, "ROTATING_VIB", 1L, "F", "D", "P", 0,
                new OperatingCondition(1500.0, null, null),
                java.util.Map.of("v_rms", 0.04, "a_peak", 0.1, "a_kurtosis", 3.0,
                        "freq_1x_amp", 0.1, "freq_2x_amp", 0.05),
                null, null);
        assertEquals("STOPPED", plugin.gate(lowRms).status());

        var lowRpm = new TelemetryEnvelope(2, "ROTATING_VIB", 2L, "F", "D", "P", 0,
                new OperatingCondition(250.0, null, null),
                java.util.Map.of("v_rms", 1.0, "a_peak", 0.1, "a_kurtosis", 3.0,
                        "freq_1x_amp", 0.1, "freq_2x_amp", 0.05),
                null, null);
        assertTrue(plugin.gate(lowRpm).stopped());

        var running = new TelemetryEnvelope(2, "ROTATING_VIB", 3L, "F", "D", "P", 0,
                new OperatingCondition(1500.0, null, null),
                java.util.Map.of("v_rms", 1.0, "a_peak", 0.1, "a_kurtosis", 3.0,
                        "freq_1x_amp", 0.1, "freq_2x_amp", 0.05),
                null, null);
        assertTrue(plugin.gate(running).assessable());
        assertFalse(plugin.gate(running).stopped());
    }

    @Test
    void spikeDetectionMatchesLegacyKeysOnVibrationFeatures() {
        var pluginRef = plugin;
        long base = 1_000_000L;
        // 尖峰判定作用于 3 点窗口的中心样本（第 2 个样本）
        spikeFilter.accept(1L, envelope(base, 1.0, 0.10), pluginRef);
        spikeFilter.accept(1L, envelope(base + 1000, 8.0, 0.10), pluginRef);
        Optional<SpikeFilter.FilteredSample> spike =
                spikeFilter.accept(1L, envelope(base + 2000, 1.0, 0.10), pluginRef);
        assertTrue(spike.isPresent());
        assertTrue(spike.get().spike(), "v_rms 单点尖峰应被抑制");

        spikeFilter.reset(2L);
        spikeFilter.accept(2L, envelope(base, 1.0, 0.10), pluginRef);
        spikeFilter.accept(2L, envelope(base + 1000, 1.0, 0.80), pluginRef);
        Optional<SpikeFilter.FilteredSample> orderSpike =
                spikeFilter.accept(2L, envelope(base + 2000, 1.0, 0.10), pluginRef);
        assertTrue(orderSpike.isPresent());
        assertTrue(orderSpike.get().spike(), "1X 幅值单点尖峰应被抑制");

        spikeFilter.reset(3L);
        spikeFilter.accept(3L, envelope(base, 1.0, 0.10), pluginRef);
        spikeFilter.accept(3L, envelope(base + 1000, 1.1, 0.11), pluginRef);
        Optional<SpikeFilter.FilteredSample> normal =
                spikeFilter.accept(3L, envelope(base + 2000, 1.0, 0.10), pluginRef);
        assertTrue(normal.isPresent());
        assertFalse(normal.get().spike());
    }

    private TelemetryEnvelope envelope(long timestamp, double vRms, double freq1x) {
        return new TelemetryEnvelope(2, "ROTATING_VIB", timestamp, "F", "D", "P", 0,
                new OperatingCondition(1500.0, null, null),
                java.util.Map.of("v_rms", vRms, "a_peak", 0.1, "a_kurtosis", 3.0,
                        "freq_1x_amp", freq1x, "freq_2x_amp", 0.05),
                List.of(), null);
    }

    private TelemetryRequest request(double vRms, double aPeak, double kurtosis, double oneX, double twoX) {
        var values = new TelemetryRequest.TelemetryValues(vRms, aPeak, kurtosis, oneX, twoX,
                null, null, 1500.0, null, null);
        return new TelemetryRequest(1_000_000L, "FACT_01", "PUMP_P102A", "DE_RADIAL", 0, values, null);
    }
}
