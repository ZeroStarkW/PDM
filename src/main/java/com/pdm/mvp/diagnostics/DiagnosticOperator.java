package com.pdm.mvp.diagnostics;

public enum DiagnosticOperator {
    GT, GTE, LT, LTE;

    boolean matches(double observed, double threshold) {
        return switch (this) {
            case GT -> observed > threshold;
            case GTE -> observed >= threshold;
            case LT -> observed < threshold;
            case LTE -> observed <= threshold;
        };
    }
}
