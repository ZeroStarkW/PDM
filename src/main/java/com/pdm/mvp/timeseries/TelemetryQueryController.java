package com.pdm.mvp.timeseries;

import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.telemetry.TelemetryPoint;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/points")
public class TelemetryQueryController {
    private final PointRepository points;
    private final TelemetryStore store;
    public TelemetryQueryController(PointRepository points, TelemetryStore store) { this.points = points; this.store = store; }

    @GetMapping("/{id}/telemetry")
    @Transactional(readOnly = true)
    public List<TelemetryPoint> query(@PathVariable Long id, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "5m") String interval) {
        if (!java.util.Set.of("1m", "5m", "15m", "1h", "6h").contains(interval))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "interval 只能是 1m、5m、15m、1h 或 6h");
        PointEntity point = points.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "测点不存在"));
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(24, ChronoUnit.HOURS) : from;
        if (start.isAfter(end)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from 不能晚于 to");
        return store.query(point, start, end, interval);
    }
}
