package com.pdm.mvp.alert;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class DeviceHealthWindow {
    private final Map<ObservationKey, ArrayDeque<Boolean>> windows = new HashMap<>();
    private final Map<ObservationKey, Long> lastTimestamps = new HashMap<>();
    public synchronized boolean observe(Long deviceId, Long pointId, long timestamp, double healthIndex) {
        return observe(deviceId, pointId, timestamp, healthIndex, true);
    }

    /** resetOnGap=false 时时间断档不清窗：事件型模态动作间隔可达数天，窗口按次数滚动。 */
    public synchronized boolean observe(Long deviceId, Long pointId, long timestamp, double healthIndex,
            boolean resetOnGap) {
        ObservationKey key = new ObservationKey(deviceId, pointId);
        Long previousTimestamp = lastTimestamps.get(key);
        if (previousTimestamp != null && timestamp <= previousTimestamp) return false;
        if (resetOnGap && previousTimestamp != null && timestamp - previousTimestamp > Duration.ofMinutes(15).toMillis())
            windows.computeIfAbsent(key, ignored -> new ArrayDeque<>(3)).clear();
        lastTimestamps.put(key, timestamp);
        ArrayDeque<Boolean> window = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>(5));
        window.addLast(healthIndex < 50.0);
        while (window.size() > 5) window.removeFirst();
        return window.size() == 5 && window.stream().filter(Boolean::booleanValue).count() >= 4;
    }
    public synchronized void reset(Long deviceId) {
        windows.keySet().removeIf(key -> key.deviceId().equals(deviceId));
        lastTimestamps.keySet().removeIf(key -> key.deviceId().equals(deviceId));
    }

    public synchronized void reset(Long deviceId, Long pointId) {
        ObservationKey key = new ObservationKey(deviceId, pointId);
        windows.remove(key);
        lastTimestamps.remove(key);
    }

    private record ObservationKey(Long deviceId, Long pointId) {}
}
