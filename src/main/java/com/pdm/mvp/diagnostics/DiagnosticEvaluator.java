package com.pdm.mvp.diagnostics;

import com.pdm.mvp.asset.DeviceTypeService;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.mcsa.McsaPlugin;
import com.pdm.mvp.modality.BaselineSnapshot;
import com.pdm.mvp.switchgear.CircuitBreakerPlugin;
import com.pdm.mvp.controlvalve.ControlValvePlugin;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import com.pdm.mvp.telemetry.TelemetryRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class DiagnosticEvaluator {
    private static final double ORDER_TOLERANCE = 0.10;
    private final DiagnosticRuleRepository rules;
    private final DeviceTypeService deviceTypes;

    public DiagnosticEvaluator(DiagnosticRuleRepository rules, DeviceTypeService deviceTypes) {
        this.rules = rules;
        this.deviceTypes = deviceTypes;
    }

    public DiagnosticMatch evaluate(PointEntity point, TelemetryRequest sample, double freq1xBaseline) {
        var device = point.getDevice();
        var type = device.getDeviceType();
        if (type == null) type = deviceTypes.general();

        var configuredRules = rules.findByDeviceType_IdAndEnabledTrueOrderByPriorityDescIdAsc(type.getId());
        double effectiveRpm = resolveOrderRpm(sample, device);
        for (var rule : configuredRules) {
            var metric = rule.getMetric();
            var order = rule.getFrequencyOrder();
            FrequencyMatch frequency = metric.requiresFrequencyOrder()
                    ? findOrderPeak(sample, effectiveRpm, order) : null;
            Double observed = observedValue(metric, sample.telemetry(), freq1xBaseline, frequency);
            if (observed == null || !Double.isFinite(observed)
                    || !rule.getOperator().matches(observed, rule.getThreshold())) {
                continue;
            }
            return new DiagnosticMatch(type.getId(), type.getTypeName(), rule.getId(), rule.getVersion(),
                    metric, rule.getOperator(), rule.getThreshold(), observed, order,
                    frequency == null ? null : frequency.peak().frequency(),
                    frequency == null ? null : frequency.targetHz(),
                    rule.getFactor(), rule.getEvidence(), rule.getAdvice());
        }
        return new DiagnosticMatch(type.getId(), type.getTypeName(), null, null, null, null, null, null, null,
                null, null,
                "综合振动变化", "当前设备类型没有命中的主导因素规则。", "现场复测并检查设备工况和安装状态。");
    }

    /** Evaluate a configured rule against a multimodal envelope; empty means the modality plugin keeps its diagnosis. */
    public Optional<DiagnosticMatch> evaluateConfigured(PointEntity point, TelemetryEnvelope sample,
            BaselineSnapshot baseline) {
        var device = point.getDevice();
        var type = device.getDeviceType() == null ? deviceTypes.general() : device.getDeviceType();
        var supported = DiagnosticMetric.supportedForDeviceType(type.getTypeCode());
        for (var rule : rules.findByDeviceType_IdAndEnabledTrueOrderByPriorityDescIdAsc(type.getId())) {
            var metric = rule.getMetric();
            if (!supported.contains(metric)) continue;
            Double observed = observedValue(metric, sample, baseline);
            if (observed == null || !Double.isFinite(observed)
                    || !rule.getOperator().matches(observed, rule.getThreshold())) continue;
            return Optional.of(new DiagnosticMatch(type.getId(), type.getTypeName(), rule.getId(), rule.getVersion(),
                    metric, rule.getOperator(), rule.getThreshold(), observed, null, null, null,
                    rule.getFactor(), rule.getEvidence(), rule.getAdvice()));
        }
        return Optional.empty();
    }

    private static Double observedValue(DiagnosticMetric metric, TelemetryEnvelope sample, BaselineSnapshot baseline) {
        return switch (metric) {
            case MCSA_SIDEBAND_GAP_DB -> difference(sample.featureOrNull(McsaPlugin.CARRIER_AMP_DB),
                    sample.featureOrNull(McsaPlugin.SIDEBAND_AMP_DB));
            case MCSA_SLIP_PCT -> sample.featureOrNull(McsaPlugin.SLIP_PCT);
            case TRIP_DURATION_RATIO -> ratio(sample.featureOrNull(CircuitBreakerPlugin.TRIP_DURATION_MS),
                    baseline.base(CircuitBreakerPlugin.TRIP_DURATION_MS));
            case COIL_CURRENT_RATIO -> ratio(sample.featureOrNull(CircuitBreakerPlugin.PEAK_COIL_CURRENT_A),
                    baseline.base(CircuitBreakerPlugin.PEAK_COIL_CURRENT_A));
            case CLOSING_VELOCITY_RATIO -> ratio(sample.featureOrNull(CircuitBreakerPlugin.CLOSING_VELOCITY_MPS),
                    baseline.base(CircuitBreakerPlugin.CLOSING_VELOCITY_MPS));
            case CONTACT_WEAR_RATIO -> sample.featureOrNull(CircuitBreakerPlugin.CONTACT_WEAR_RATIO);
            case VALVE_DEADBAND_RATIO -> ratio(sample.featureOrNull(ControlValvePlugin.HYSTERESIS_DEADBAND_PCT),
                    baseline.base(ControlValvePlugin.HYSTERESIS_DEADBAND_PCT));
            case VALVE_LEAK_RMS -> sample.featureOrNull(ControlValvePlugin.ULTRASONIC_LEAK_RMS);
            case VALVE_TRAVEL_TIME_RATIO -> ratio(sample.featureOrNull(ControlValvePlugin.TRAVEL_TIME_LAG_MS),
                    baseline.base(ControlValvePlugin.TRAVEL_TIME_LAG_MS));
            case VALVE_FRICTION_RATIO -> ratio(sample.featureOrNull(ControlValvePlugin.FRICTION_FORCE_KN),
                    baseline.base(ControlValvePlugin.FRICTION_FORCE_KN));
            default -> null;
        };
    }

    private static Double difference(Double left, Double right) {
        return left == null || right == null ? null : left - right;
    }

    private static Double observedValue(DiagnosticMetric metric, TelemetryRequest.TelemetryValues values,
            double baseline, FrequencyMatch frequency) {
        return switch (metric) {
            case FREQ_1X_AMP -> values.freq1xAmp();
            case FREQ_1X_RATIO -> ratio(values.freq1xAmp(), baseline);
            case FREQ_1X_OVER_2X_RATIO -> ratio(values.freq1xAmp(), values.freq2xAmp());
            case FREQ_2X_AMP -> values.freq2xAmp();
            case FREQ_2X_OVER_1X_RATIO -> ratio(values.freq2xAmp(), values.freq1xAmp());
            case V_RMS -> values.vRms();
            case A_PEAK -> values.aPeak();
            case A_KURTOSIS -> values.aKurtosis();
            case ENVELOPE_RMS -> values.envelopeRms();
            case TEMPERATURE -> values.temperature();
            case ORDER_PEAK_AMPLITUDE -> frequency == null ? null : frequency.peak().amplitude();
            case ORDER_PEAK_SNR -> frequency == null ? null : frequency.peak().snr();
            case MCSA_SIDEBAND_GAP_DB, MCSA_SLIP_PCT, TRIP_DURATION_RATIO, COIL_CURRENT_RATIO,
                    CLOSING_VELOCITY_RATIO, CONTACT_WEAR_RATIO, VALVE_DEADBAND_RATIO, VALVE_LEAK_RMS,
                    VALVE_TRAVEL_TIME_RATIO, VALVE_FRICTION_RATIO -> null;
        };
    }

    private static Double ratio(Double numerator, Double denominator) {
        return numerator == null || denominator == null || denominator <= 0 ? null : numerator / denominator;
    }

    private static double resolveOrderRpm(TelemetryRequest sample, com.pdm.mvp.asset.DeviceEntity device) {
        Double liveRpm = sample.telemetry() == null ? null : sample.telemetry().rpm();
        if (liveRpm != null && Double.isFinite(liveRpm) && liveRpm > 0) return liveRpm;
        if (device != null && device.getRpmNominal() != null && device.getRpmNominal().doubleValue() > 0) {
            return device.getRpmNominal().doubleValue();
        }
        return 0.0;
    }

    private static FrequencyMatch findOrderPeak(TelemetryRequest sample, double rpm, Double order) {
        if (sample.topPeaks() == null || order == null || !Double.isFinite(rpm) || rpm <= 0) return null;

        double targetHz = rpm / 60.0 * order;
        double toleranceHz = targetHz * ORDER_TOLERANCE;
        TelemetryRequest.TopPeak nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (var peak : sample.topPeaks()) {
            if (peak == null || peak.frequency() == null) continue;
            double distance = Math.abs(peak.frequency() - targetHz);
            if (distance <= toleranceHz && distance < nearestDistance) {
                nearest = peak;
                nearestDistance = distance;
            }
        }
        return nearest == null ? null : new FrequencyMatch(nearest, targetHz);
    }

    private record FrequencyMatch(TelemetryRequest.TopPeak peak, double targetHz) {}
}
