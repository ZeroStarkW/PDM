package com.pdm.mvp.telemetry;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * PHM 协议 2.1.0 慢周期特征遥测 REST 入口（文档 §2.1），与 MQTT phm/telemetry 主题共用载荷与服务路径。
 */
@RestController
@RequestMapping("/api/v2/phm")
public class PhmTelemetryController {
    private final TelemetryService service;
    public PhmTelemetryController(TelemetryService service) { this.service = service; }

    @PostMapping("/telemetry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public TelemetryResult accept(@Valid @RequestBody PhmTelemetryRequest request) {
        return service.accept(request, "REST");
    }
}
