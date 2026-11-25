package com.pdm.mvp.alert;

import java.time.Instant;

public record NotificationDelivery(Long id, Long alertId, NotificationEvent.Kind eventKind,
        NotificationEvent.State state, int attempts, Instant nextAttemptAt, String lastError,
        Instant createdAt, Instant sentAt) {}
