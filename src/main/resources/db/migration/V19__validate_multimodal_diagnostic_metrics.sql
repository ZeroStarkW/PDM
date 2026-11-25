-- Validate the expanded allow-list after V18 committed it as NOT VALID.
-- PostgreSQL can validate an existing CHECK while allowing ordinary reads/writes.
ALTER TABLE pdm_diagnostic_rule
    VALIDATE CONSTRAINT chk_diagnostic_rule_metric_supported;
