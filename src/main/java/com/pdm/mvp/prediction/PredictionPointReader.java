package com.pdm.mvp.prediction;

import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.DeviceRepository;
import com.pdm.mvp.asset.FactoryEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class PredictionPointReader {
    private final DeviceRepository devices;
    private final PointRepository points;

    PredictionPointReader(DeviceRepository devices, PointRepository points) {
        this.devices = devices;
        this.points = points;
    }

    @Transactional(readOnly = true)
    public List<PointSnapshot> load(Long deviceId) {
        DeviceEntity device = devices.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在"));
        if (DeviceEntity.isOutOfService(device)) return List.of();
        String factoryCode = device.getFactory().getFactoryCode();
        String deviceCode = device.getDeviceCode();
        return points.findByDevice_IdOrderByPointCode(deviceId).stream()
                .map(point -> new PointSnapshot(point.getId(), factoryCode, deviceCode, point.getPointCode(),
                        point.getPointName(), point.getBaselineStatus(), point.getBaselineLockedAt(),
                        point.getLastHealthIndex(), point.getLastTelemetryAt(), point.getIngestedAt(), point.getQualityCode(),
                        point.getModalityType()))
                .toList();
    }

    record PointSnapshot(Long id, String factoryCode, String deviceCode, String pointCode, String pointName,
            short baselineStatus, Instant baselineLockedAt, Double lastHealthIndex, Instant lastTelemetryAt,
            Instant ingestedAt, Integer qualityCode, String modalityType) {
        PointEntity telemetryLookup() {
            // TelemetryStore reads only the codes and whether telemetry has ever arrived.
            FactoryEntity factory = new FactoryEntity(factoryCode, factoryCode);
            DeviceEntity device = new DeviceEntity(factory, deviceCode, deviceCode, null, null, null);
            PointEntity point = new PointEntity(device, pointCode, pointName, null);
            point.setLastTelemetryAt(lastTelemetryAt);
            return point;
        }
    }
}
