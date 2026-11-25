package com.pdm.mvp.analytics;

import com.pdm.mvp.alert.AlertService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices/{deviceId}/baseline")
public class DeviceBaselineController {
    private final BaselineService baselines;
    private final AlertService alerts;

    public DeviceBaselineController(BaselineService baselines, AlertService alerts) {
        this.baselines = baselines;
        this.alerts = alerts;
    }

    @PostMapping("/reset-request")
    @PreAuthorize("hasRole('ADMIN')")
    public BaselineService.DeviceBaselineOperation requestReset(@PathVariable Long deviceId,
            @Valid @RequestBody(required = false) DeviceBaselineRequest request) {
        var result = baselines.requestDeviceReset(deviceId, request == null ? null : request.pointIds());
        resetDebounceFor(result);
        return result;
    }

    @PostMapping("/confirm-reset")
    @PreAuthorize("hasRole('ADMIN')")
    public BaselineService.DeviceBaselineOperation confirmReset(@PathVariable Long deviceId,
            @Valid @RequestBody(required = false) DeviceBaselineRequest request) {
        var result = baselines.confirmDeviceReset(deviceId, request == null ? null : request.pointIds());
        resetDebounceFor(result);
        return result;
    }

    private void resetDebounceFor(BaselineService.DeviceBaselineOperation operation) {
        operation.baselines().forEach(state -> alerts.resetDebounce(state.pointId()));
    }

    public record DeviceBaselineRequest(List<@Positive Long> pointIds) {}
}
