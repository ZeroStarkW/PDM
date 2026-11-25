package com.pdm.mvp.telemetry;

public record TelemetryResult(boolean accepted, Long pointId, String status, Double healthIndex, String message) {}
