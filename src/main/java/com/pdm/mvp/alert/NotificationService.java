package com.pdm.mvp.alert;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {
    private final NotificationRepository events;
    private final AlertRepository alerts;
    private final WebhookNotifier webhook;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Duration acknowledgementSla;

    public NotificationService(NotificationRepository events, AlertRepository alerts, WebhookNotifier webhook,
            @Value("${pdm.notifications.max-attempts:8}") int maxAttempts,
            @Value("${pdm.notifications.initial-backoff:PT30S}") Duration initialBackoff,
            @Value("${pdm.notifications.max-backoff:PT1H}") Duration maxBackoff,
            @Value("${pdm.notifications.acknowledgement-sla:PT30M}") Duration acknowledgementSla) {
        if (maxAttempts < 1 || maxAttempts > 8 || !initialBackoff.isPositive() ||
                maxBackoff.compareTo(initialBackoff) < 0 || !acknowledgementSla.isPositive()) {
            throw new IllegalArgumentException("Invalid notification attempt limit, backoff duration or acknowledgement SLA");
        }
        this.events = events;
        this.alerts = alerts;
        this.webhook = webhook;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.acknowledgementSla = acknowledgementSla;
    }

    @Transactional
    public void enqueueEscalations() {
        events.enqueueEscalations(Instant.now().minus(acknowledgementSla));
    }

    @Transactional
    public boolean deliverNext() {
        if (!webhook.enabled()) return false;
        var due = events.lockNextDue();
        if (due.isEmpty()) return false;
        // Hold one event lock across the bounded HTTP call and commit before claiming the next event.
        var event = due.get();
        event.beginAttempt();
        var error = webhook.send(event, maxAttempts);
        Instant now = Instant.now();
        if (error.isPresent()) {
            var delay = initialBackoff.multipliedBy(1L << (event.attempts() - 1));
            var nextAttempt = delay.compareTo(maxBackoff) < 0 ? delay : maxBackoff;
            event.failed(error.get(), event.attempts() >= maxAttempts ? null : now.plus(nextAttempt));
        } else {
            event.delivered(now);
            if (event.kind() == NotificationEvent.Kind.ALERT) alerts.markNotified(event.alertId());
        }
        return true;
    }

    @Transactional
    public NotificationDelivery retry(Long id) {
        var event = events.lockById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在"));
        if (event.state() != NotificationEvent.State.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能重试已失败的通知");
        }
        event.requeue(Instant.now());
        return event.delivery();
    }

    @Transactional(readOnly = true)
    public FailedPage failed(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码超出范围或每页数量不在 1–100 之间");
        }
        var results = events.findByStateOrderByIdDesc(NotificationEvent.State.FAILED, PageRequest.of(page, size));
        return new FailedPage(results.getContent(), page, results.getTotalPages(), results.getTotalElements());
    }

    public record FailedPage(List<NotificationDelivery> items, int page, int totalPages, long totalElements) {}
}
