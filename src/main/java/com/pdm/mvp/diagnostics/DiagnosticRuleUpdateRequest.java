package com.pdm.mvp.diagnostics;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record DiagnosticRuleUpdateRequest(
        @NotNull DiagnosticMetric metric,
        Double frequencyOrder,
        @NotNull DiagnosticOperator operator,
        @NotNull Double threshold,
        @NotNull @Min(0) @Max(10000) Integer priority,
        @NotBlank @Size(max = 128) String factor,
        @NotBlank @Size(max = 512) String evidence,
        @NotBlank @Size(max = 512) String advice,
        @NotNull Boolean enabled,
        @NotNull @PositiveOrZero Long expectedVersion) {

    DiagnosticRuleRequest settings() {
        return new DiagnosticRuleRequest(metric, frequencyOrder, operator, threshold, priority, factor, evidence,
                advice, enabled);
    }
}
