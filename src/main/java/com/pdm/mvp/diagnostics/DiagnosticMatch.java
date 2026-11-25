package com.pdm.mvp.diagnostics;

public record DiagnosticMatch(Long deviceTypeId, String deviceTypeName, Long ruleId, Long ruleVersion,
        DiagnosticMetric metric, DiagnosticOperator operator, Double threshold, Double observedValue,
        Double frequencyOrder, Double observedFrequencyHz, Double targetFrequencyHz,
        String factor, String evidence, String advice) {}
