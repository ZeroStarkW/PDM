package com.pdm.mvp.maintenance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/maintenance")
public class MaintenanceController {
    private final MaintenanceService maintenance;

    public MaintenanceController(MaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    @GetMapping("/plans")
    public MaintenanceService.PageView<MaintenanceService.PlanView> listPlans(
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        validateOffset(page, size);
        return maintenance.listPlans(page, size);
    }

    @GetMapping("/plans/{id}")
    public MaintenanceService.PlanView getPlan(@PathVariable Long id) {
        return maintenance.getPlan(id);
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public MaintenanceService.PlanView createPlan(@Valid @RequestBody PlanRequest request) {
        return maintenance.createPlan(request);
    }

    @PutMapping("/plans/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public MaintenanceService.PlanView updatePlan(@PathVariable Long id, @Valid @RequestBody PlanRequest request) {
        return maintenance.updatePlan(id, request);
    }

    @GetMapping("/tasks")
    public MaintenanceService.PageView<MaintenanceService.TaskView> listTasks(
            @RequestParam(defaultValue = "ALL") MaintenanceService.TaskFilter filter,
            @RequestParam(defaultValue = "0") @PositiveOrZero int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        validateOffset(page, size);
        return maintenance.listTasks(filter, page, size);
    }

    @PostMapping("/tasks/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public MaintenanceService.TaskView assign(@PathVariable Long id, @Valid @RequestBody AssignRequest request) {
        return maintenance.assign(id, request.assignee());
    }

    @PostMapping("/tasks/{id}/start")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public MaintenanceService.TaskView start(@PathVariable Long id) {
        return maintenance.start(id);
    }

    @PostMapping("/tasks/{id}/complete")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public MaintenanceService.TaskView complete(@PathVariable Long id, @Valid @RequestBody CompleteRequest request) {
        return maintenance.complete(id, request.findings(), request.action());
    }

    @PostMapping("/tasks/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public MaintenanceService.TaskView cancel(@PathVariable Long id) {
        return maintenance.cancel(id);
    }

    private static void validateOffset(int page, int size) {
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页偏移量超出允许范围");
        }
    }

    public record PlanRequest(@NotNull @Positive Long deviceId, @NotBlank @Size(max = 128) String title,
            @NotNull @Positive @Max(36500) Integer intervalDays, @NotNull Instant nextDueAt,
            @Size(max = 2000) String instructions,
            @Size(max = 80) String assignee, @NotNull Boolean enabled, @PositiveOrZero Long expectedVersion) {}

    public record AssignRequest(@NotBlank @Size(max = 80) String assignee) {}

    public record CompleteRequest(@NotBlank @Size(max = 2000) String findings,
            @NotBlank @Size(max = 2000) String action) {}
}
