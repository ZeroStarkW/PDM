package com.pdm.mvp.telemetry;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 版本化统一契约入口：接收带模态类型的遥测契约（含 MCSA 特征），与旧 /api/v1/telemetry 并行。
 */
@RestController
@RequestMapping("/api/v2/telemetry")
public class TelemetryContractController {
    private final TelemetryService service;
    public TelemetryContractController(TelemetryService service) { this.service = service; }

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public TelemetryResult accept(@Valid @RequestBody ContractTelemetryRequest request) {
        return service.accept(request.toEnvelope(), "REST");
    }
}
