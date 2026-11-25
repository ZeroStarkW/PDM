-- V4 constrained rules to vibration-only metrics. Replace that allow-list so rules
-- for MCSA, circuit breakers, and control valves can be stored by PostgreSQL.
-- Forward-only: reverting requires deleting all rows that use the added metrics,
-- then restoring the original allow-list in a new migration.
ALTER TABLE pdm_diagnostic_rule
    DROP CONSTRAINT IF EXISTS pdm_diagnostic_rule_metric_check;

ALTER TABLE pdm_diagnostic_rule
    ADD CONSTRAINT chk_diagnostic_rule_metric_supported
    CHECK (metric IN (
        'FREQ_1X_AMP', 'FREQ_1X_RATIO', 'FREQ_1X_OVER_2X_RATIO', 'FREQ_2X_AMP', 'FREQ_2X_OVER_1X_RATIO',
        'V_RMS', 'A_PEAK', 'A_KURTOSIS', 'ENVELOPE_RMS', 'TEMPERATURE', 'ORDER_PEAK_AMPLITUDE', 'ORDER_PEAK_SNR',
        'MCSA_SIDEBAND_GAP_DB', 'MCSA_SLIP_PCT',
        'TRIP_DURATION_RATIO', 'COIL_CURRENT_RATIO', 'CLOSING_VELOCITY_RATIO', 'CONTACT_WEAR_RATIO',
        'VALVE_DEADBAND_RATIO', 'VALVE_LEAK_RMS', 'VALVE_TRAVEL_TIME_RATIO', 'VALVE_FRICTION_RATIO'
    )) NOT VALID;
