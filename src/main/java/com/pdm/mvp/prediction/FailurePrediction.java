package com.pdm.mvp.prediction;

import java.time.Instant;

public record FailurePrediction(Long deviceId, Status status, Long sourcePointId, String sourcePointCode,
        String sourcePointName, Double currentHi, Double slopePerDay, Double projectedDays, int observationCount,
        Instant windowStart, Instant windowEnd, Double fitScore, Confidence confidence) {
    public enum Status { INSUFFICIENT_DATA, NO_DECLINE, THRESHOLD_REACHED, ESTIMATE_AVAILABLE }
    public enum Confidence { LOW, MEDIUM, HIGH }
}
