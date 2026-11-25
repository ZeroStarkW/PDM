package com.pdm.mvp.telemetry;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {
    private final TelemetryService service;
    public TelemetryController(TelemetryService service) { this.service = service; }
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public TelemetryResult accept(@Valid @RequestBody TelemetryRequest request) { return service.accept(request, "REST"); }
}
