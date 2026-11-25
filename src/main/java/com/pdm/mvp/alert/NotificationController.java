package com.pdm.mvp.alert;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) { this.notifications = notifications; }

    @GetMapping("/failed")
    public NotificationService.FailedPage failed(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return notifications.failed(page, size);
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public NotificationDelivery retry(@PathVariable Long id) { return notifications.retry(id); }
}
