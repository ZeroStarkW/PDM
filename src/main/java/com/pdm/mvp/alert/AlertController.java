package com.pdm.mvp.alert;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {
    private final AlertService alerts;
    public AlertController(AlertService alerts) { this.alerts = alerts; }

    @GetMapping public List<AlertService.AlertView> list() { return alerts.list(); }

    /** 服务端分页 + 多维筛选（车间/设备/级别/状态/关键字）；无参 list() 行为保持不变供存量调用方使用。 */
    @GetMapping("/page")
    public AlertService.AlertPageView page(
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        validateOffset(page, size);
        return alerts.listPage(page, size, factoryCode, deviceId, level, status, keyword);
    }

    static void validateOffset(int page, int size) {
        if ((long) page * size > 100_000L)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页偏移量超出允许范围");
    }

    @GetMapping("/{id}") public AlertService.AlertView get(@PathVariable Long id) { return alerts.get(id); }

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public AlertService.AlertView acknowledge(@PathVariable Long id) { return alerts.acknowledge(id); }

    @PostMapping("/{id}/suppress")
    @PreAuthorize("hasRole('ADMIN')")
    public AlertService.AlertView suppress(@PathVariable Long id, @Valid @RequestBody SuppressRequest request) {
        return alerts.suppress(id, request.note());
    }

    public record SuppressRequest(@NotBlank String note) {}
}
