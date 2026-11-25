package com.pdm.mvp.diagnostics;

import java.util.Set;

public enum DiagnosticMetric {
    FREQ_1X_AMP,
    FREQ_1X_RATIO,
    FREQ_1X_OVER_2X_RATIO,
    FREQ_2X_AMP,
    FREQ_2X_OVER_1X_RATIO,
    V_RMS,
    A_PEAK,
    A_KURTOSIS,
    ENVELOPE_RMS,
    TEMPERATURE,
    ORDER_PEAK_AMPLITUDE,
    ORDER_PEAK_SNR,
    MCSA_SIDEBAND_GAP_DB,
    MCSA_SLIP_PCT,
    TRIP_DURATION_RATIO,
    COIL_CURRENT_RATIO,
    CLOSING_VELOCITY_RATIO,
    CONTACT_WEAR_RATIO,
    VALVE_DEADBAND_RATIO,
    VALVE_LEAK_RMS,
    VALVE_TRAVEL_TIME_RATIO,
    VALVE_FRICTION_RATIO;

    private static final Set<DiagnosticMetric> VIBRATION = Set.of(
            FREQ_1X_AMP, FREQ_1X_RATIO, FREQ_1X_OVER_2X_RATIO, FREQ_2X_AMP, FREQ_2X_OVER_1X_RATIO,
            V_RMS, A_PEAK, A_KURTOSIS, ENVELOPE_RMS, TEMPERATURE, ORDER_PEAK_AMPLITUDE, ORDER_PEAK_SNR);
    private static final Set<DiagnosticMetric> MOTOR_METRICS = Set.of(MCSA_SIDEBAND_GAP_DB, MCSA_SLIP_PCT);
    private static final Set<DiagnosticMetric> BREAKER_METRICS = Set.of(TRIP_DURATION_RATIO, COIL_CURRENT_RATIO,
            CLOSING_VELOCITY_RATIO, CONTACT_WEAR_RATIO);
    private static final Set<DiagnosticMetric> VALVE_METRICS = Set.of(VALVE_DEADBAND_RATIO, VALVE_LEAK_RMS,
            VALVE_TRAVEL_TIME_RATIO, VALVE_FRICTION_RATIO);
    private static final Set<DiagnosticMetric> ELECTRIC_MOTOR = withVibration(MOTOR_METRICS);
    private static final Set<DiagnosticMetric> CIRCUIT_BREAKER = withVibration(BREAKER_METRICS);
    private static final Set<DiagnosticMetric> CONTROL_VALVE = withVibration(VALVE_METRICS);

    boolean requiresFrequencyOrder() {
        return this == ORDER_PEAK_AMPLITUDE || this == ORDER_PEAK_SNR;
    }

    public static Set<DiagnosticMetric> supportedForDeviceType(String typeCode) {
        return switch (typeCode == null ? "" : typeCode) {
            case "ELECTRIC_MOTOR" -> ELECTRIC_MOTOR;
            case "CIRCUIT_BREAKER" -> CIRCUIT_BREAKER;
            case "CONTROL_VALVE" -> CONTROL_VALVE;
            default -> VIBRATION;
        };
    }

    private static Set<DiagnosticMetric> withVibration(Set<DiagnosticMetric> modalityMetrics) {
        java.util.EnumSet<DiagnosticMetric> result = java.util.EnumSet.copyOf(VIBRATION);
        result.addAll(modalityMetrics);
        return Set.copyOf(result);
    }
}
