package com.pdm.mvp.analytics;

import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.PointEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Equipment-level health derived from independent point assessments. */
public final class DeviceHealthRollup {
    private DeviceHealthRollup() {}

    public static Summary assess(DeviceEntity device, List<PointEntity> points, Instant now, Duration staleAfter) {
        return assess(device, points, now, modality -> staleAfter);
    }

    /** 按测点模态解析新鲜度时限：事件型模态（如断路器）动作间隔可达数天，不应被判为 STALE。 */
    public static Summary assess(DeviceEntity device, List<PointEntity> points, Instant now,
            java.util.function.Function<String, Duration> staleAfterByModality) {
        String outOfService = DeviceEntity.outOfServiceStatus(device);
        List<Evidence> evidence = points.stream()
                .map(point -> evidence(point, now.minus(staleAfterByModality.apply(point.getModalityType())), outOfService))
                .toList();
        List<Evidence> scored = outOfService != null ? List.of() : evidence.stream().filter(Evidence::scored).toList();
        Evidence worst = scored.stream().min(Comparator.comparingDouble(Evidence::healthIndex)).orElse(null);
        Double healthIndex = worst == null ? null : worst.healthIndex();
        String status = outOfService != null ? outOfService
                : healthIndex != null ? statusFor(healthIndex)
                : evidence.isEmpty() ? "NO_POINTS"
                : evidence.stream().anyMatch(point -> "BASELINE_LEARNING".equals(point.status())) ? "BASELINE_LEARNING" : "NO_DATA";
        long online = evidence.stream().filter(p -> "ONLINE".equals(p.freshness())
                && !"SENSOR_CHECK".equals(p.status()) && !"STOPPED".equals(p.status())
                && !"CONDITION_REJECTED".equals(p.status())
                && !"MAINTENANCE".equals(p.status()) && !"RETIRED".equals(p.status())).count();
        long stale = evidence.stream().filter(p -> "STALE".equals(p.freshness())).count();
        long sensorCheck = evidence.stream().filter(p -> "SENSOR_CHECK".equals(p.status())).count();
        long stopped = evidence.stream().filter(p -> "STOPPED".equals(p.status())
                || "MAINTENANCE".equals(p.status()) || "RETIRED".equals(p.status())).count();
        long learning = evidence.stream().filter(p -> "BASELINE_LEARNING".equals(p.status())).count();
        String coverage = points.isEmpty() ? "NONE" : scored.size() == points.size() ? "COMPLETE" : scored.isEmpty() ? "NONE" : "PARTIAL";
        return new Summary(device.getId(), device.getFactory().getFactoryCode(), device.getDeviceCode(),
                device.getDeviceName(), device.getDeviceType().getTypeName(), healthIndex, status,
                "WORST_VALID_POINT", coverage,
                scored.size(), points.size(), online, stale, sensorCheck, stopped, learning,
                worst == null ? null : worst.pointId(), worst == null ? null : worst.pointCode(), evidence);
    }

    private static Evidence evidence(PointEntity point, Instant staleCutoff, String outOfService) {
        String rawStatus = point.getLastStatus();
        String freshness = point.getIngestedAt() == null ? "NO_DATA"
                : point.getIngestedAt().isBefore(staleCutoff) ? "STALE" : "ONLINE";
        if (outOfService != null) {
            return new Evidence(point.getId(), point.getPointCode(), point.getPointName(), null,
                    outOfService, freshness, point.getQualityCode(),
                    point.getLastTelemetryAt() == null ? null : point.getLastTelemetryAt().toString(), false);
        }
        String status = "SENSOR_CHECK".equals(rawStatus) || point.getQualityCode() != null && point.getQualityCode() != 0
                ? "SENSOR_CHECK"
                : "MAINTENANCE".equals(rawStatus) || "RETIRED".equals(rawStatus) ? rawStatus
                : "STOPPED".equals(rawStatus) || "STOPPED_OR_SENSOR_CHECK".equals(rawStatus) ? "STOPPED"
                : com.pdm.mvp.modality.ModalityTypes.CONDITION_REJECTED.equals(rawStatus) ? "CONDITION_REJECTED"
                : point.getBaselineStatus() != BaselineService.ACTIVE || point.getLastHealthIndex() == null
                        ? "BASELINE_LEARNING" : statusFor(point.getLastHealthIndex());
        boolean scored = point.getBaselineStatus() == BaselineService.ACTIVE && point.getLastHealthIndex() != null && "ONLINE".equals(freshness)
                && !"SENSOR_CHECK".equals(status) && !"STOPPED".equals(status)
                && !"CONDITION_REJECTED".equals(status)
                && !"MAINTENANCE".equals(status) && !"RETIRED".equals(status);
        return new Evidence(point.getId(), point.getPointCode(), point.getPointName(), point.getLastHealthIndex(),
                status, freshness, point.getQualityCode(), point.getLastTelemetryAt() == null ? null : point.getLastTelemetryAt().toString(), scored);
    }

    private static String statusFor(double healthIndex) {
        return healthIndex < 30 ? "DANGER" : healthIndex < 50 ? "WARNING" : healthIndex < 70 ? "ATTENTION" : "HEALTHY";
    }

    public record Evidence(Long pointId, String pointCode, String pointName, Double healthIndex, String status,
            String freshness, Integer qualityCode, String lastTelemetryAt, boolean scored) {}

    public record Summary(Long deviceId, String factoryCode, String deviceCode, String deviceName,
            String deviceTypeName, Double healthIndex, String status, String scoringMethod, String coverage, int scoredPointCount,
            int pointCount, long onlinePointCount, long stalePointCount, long sensorCheckPointCount,
            long stoppedPointCount, long learningPointCount, Long worstPointId, String worstPointCode,
            List<Evidence> points) {}
}
