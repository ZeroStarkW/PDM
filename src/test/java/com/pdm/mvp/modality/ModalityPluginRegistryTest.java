package com.pdm.mvp.modality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.pdm.mvp.analytics.HealthAssessment;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.diagnostics.DiagnosticMatch;
import com.pdm.mvp.telemetry.TelemetryEnvelope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ModalityPluginRegistryTest {
    @Test
    void duplicateModalityRegistrationFailsStartup() {
        var first = new StubPlugin("ROTATING_VIB");
        var second = new StubPlugin("ROTATING_VIB");
        var exception = assertThrows(IllegalStateException.class,
                () -> new ModalityPluginRegistry(List.of(first, second)));
        assertEquals(true, exception.getMessage().contains("重复注册的模态插件"));
    }

    @Test
    void unknownModalityIsRejectedBeforeScoring() {
        var registry = new ModalityPluginRegistry(List.of(new StubPlugin("ROTATING_VIB")));
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> registry.require("MCSA"));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void registeredPluginIsResolvableByModality() {
        var plugin = new StubPlugin("MCSA");
        var registry = new ModalityPluginRegistry(List.of(plugin));
        assertSame(plugin, registry.require("MCSA"));
    }

    private static final class StubPlugin implements ModalityPlugin {
        private final String type;
        StubPlugin(String type) { this.type = type; }
        @Override public String modalityType() { return type; }
        @Override public Set<String> requiredFeatures() { return Set.of("f1"); }
        @Override public ConditionGate gate(TelemetryEnvelope envelope) { return ConditionGate.accept(); }
        @Override public Set<String> spikeFeatureCodes() { return Set.of("f1"); }
        @Override public Set<String> baselineFeatureCodes() { return Set.of("f1"); }
        @Override public Set<String> scoringBaselineFeatures() { return Set.of("f1"); }
        @Override public Map<String, Double> baselineFeatureValues(TelemetryEnvelope envelope) { return Map.of(); }
        @Override public DiagnosticMatch diagnose(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline) { return null; }
        @Override public HealthAssessment score(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline,
                DiagnosticMatch diagnosis) { return null; }
        @Override public AlertTrigger alertTrigger(PointEntity point, TelemetryEnvelope sample, BaselineSnapshot baseline) { return null; }
        @Override public boolean alertsEnabled() { return false; }
    }
}
