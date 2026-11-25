package com.pdm.mvp.dashboard;

import com.pdm.mvp.alert.AlertRepository;
import com.pdm.mvp.asset.DeviceRepository;
import com.pdm.mvp.asset.FactoryRepository;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.analytics.DeviceHealthRollup;
import com.pdm.mvp.modality.TelemetryFreshnessPolicy;
import com.pdm.mvp.workorder.WorkOrderRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {
    private final FactoryRepository factories;
    private final DeviceRepository devices;
    private final PointRepository points;
    private final AlertRepository alerts;
    private final WorkOrderRepository workOrders;
    private final TelemetryFreshnessPolicy freshness;

    public DashboardService(FactoryRepository factories, DeviceRepository devices, PointRepository points,
            AlertRepository alerts, WorkOrderRepository workOrders, TelemetryFreshnessPolicy freshness) {
        this.factories = factories; this.devices = devices; this.points = points; this.alerts = alerts; this.workOrders = workOrders;
        this.freshness = freshness;
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        List<PointEntity> all = points.findAllWithDeviceContext();
        // 新鲜度按测点模态解析：事件型模态（如断路器）不应因动作间隔数天被判为 STALE
        List<PointHealth> health = all.stream()
                .map(point -> PointHealth.from(point, Instant.now().minus(freshness.staleAfter(point)))).toList();
        var grouped = all.stream().collect(java.util.stream.Collectors.groupingBy(point -> point.getDevice().getId()));
        List<DeviceHealthRollup.Summary> deviceHealth = devices.findAllWithHealthContext().stream()
                .map(device -> DeviceHealthRollup.assess(device, grouped.getOrDefault(device.getId(), List.of()),
                        Instant.now(), freshness.byModality()))
                .toList();
        long healthy = countDevices(deviceHealth, "HEALTHY");
        long attention = countDevices(deviceHealth, "ATTENTION");
        long warning = countDevices(deviceHealth, "WARNING");
        long danger = countDevices(deviceHealth, "DANGER");
        long unscored = countDevices(deviceHealth, "NO_DATA", "NO_POINTS");
        long sensorCheck = health.stream().filter(point -> point.qualityCode() == null
                ? "SENSOR_CHECK".equals(point.status()) : point.qualityCode() != 0).count();
        long stopped = countPoints(health, "STOPPED");
        long stale = health.stream().filter(point -> point.freshness() == Freshness.STALE).count();
        long learning = countDevices(deviceHealth, "BASELINE_LEARNING");
        long openAlerts = alerts.countDistinctDevicesByStatusIn(List.of((short) 0, (short) 1));
        long openOrders = workOrders.countByStatusIn(List.of("OPEN", "IN_PROGRESS"));
        return new Overview(factories.count(), devices.count(), all.size(), healthy, attention, warning, danger,
                unscored, sensorCheck, stopped, stale, learning, openAlerts, openOrders, deviceHealth, health);
    }

    private static long countDevices(List<DeviceHealthRollup.Summary> devices, String... statuses) {
        return devices.stream().filter(device -> java.util.Arrays.asList(statuses).contains(device.status())).count();
    }

    private static long countPoints(List<PointHealth> points, String... statuses) {
        return points.stream().filter(point -> java.util.Arrays.asList(statuses).contains(point.status())).count();
    }

    public record Overview(long factoryCount, long deviceCount, long pointCount, long healthyCount,
            long attentionCount, long warningCount, long dangerCount, long unscoredDeviceCount, long sensorCheckCount,
            long stoppedCount, long staleCount, long learningCount, long activeAlertCount, long openWorkOrderCount,
            List<DeviceHealthRollup.Summary> devices, List<PointHealth> points) {}

    public enum Freshness { NO_DATA, ONLINE, STALE }

    public record PointHealth(Long pointId, Long deviceId, String factoryCode, String deviceCode, String deviceName,
            String pointCode, String pointName, String modalityType, Double healthIndex, String status, short baselineStatus,
            Double freq1xBase, Double vBaseRms, int learnedSamples, Instant lastTelemetryAt,
            Integer qualityCode, Instant sampledAt, Instant ingestedAt, Freshness freshness) {
        static PointHealth from(PointEntity point, Instant staleCutoff) {
            var device = point.getDevice();
            Instant sampledAt = point.getLastReceivedAt();
            Instant ingestedAt = point.getIngestedAt();
            String status = switch (point.getLastStatus()) {
                case null -> "NO_DATA";
                case "STOPPED_OR_SENSOR_CHECK" -> "STOPPED";
                default -> point.getLastStatus();
            };
            Freshness freshness = resolveFreshness(sampledAt, ingestedAt, staleCutoff);
            return new PointHealth(point.getId(), device.getId(), device.getFactory().getFactoryCode(), device.getDeviceCode(),
                    device.getDeviceName(), point.getPointCode(), point.getPointName(), point.getModalityType(),
                    point.getLastHealthIndex(),
                    status, point.getBaselineStatus(), point.getFreq1xBase(), point.getVBaseRms(),
                    point.getLearnedSamples(), point.getLastTelemetryAt(), point.getQualityCode(),
                    sampledAt, ingestedAt, freshness);
        }

        private static Freshness resolveFreshness(Instant sampledAt, Instant ingestedAt, Instant staleCutoff) {
            if (ingestedAt == null) return sampledAt == null ? Freshness.NO_DATA : Freshness.STALE;
            return ingestedAt.isBefore(staleCutoff) ? Freshness.STALE : Freshness.ONLINE;
        }
    }
}
