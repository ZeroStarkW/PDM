package com.pdm.mvp.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "pdm_notification_outbox")
public class NotificationEvent {
    public enum Kind { ALERT, ESCALATION }
    public enum State { PENDING, DELIVERED, FAILED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "alert_id", nullable = false, updatable = false)
    private Long alertId;
    @Enumerated(EnumType.STRING) @Column(name = "event_kind", nullable = false, updatable = false, length = 16)
    private Kind eventKind;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false, updatable = false)
    private Map<String, Object> payload;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private State state;
    private int attempts;
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    @Column(name = "last_error", length = 160)
    private String lastError;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "sent_at")
    private Instant sentAt;

    protected NotificationEvent() {}

    Long id() { return id; }
    Long alertId() { return alertId; }
    Kind kind() { return eventKind; }
    State state() { return state; }
    Map<String, Object> payload() { return payload; }
    int attempts() { return attempts; }

    void beginAttempt() { attempts++; }

    void delivered(Instant now) {
        state = State.DELIVERED;
        sentAt = now;
        nextAttemptAt = null;
        lastError = null;
    }

    void failed(String detail, Instant nextAttempt) {
        lastError = detail;
        nextAttemptAt = nextAttempt;
        state = nextAttempt == null ? State.FAILED : State.PENDING;
    }

    void requeue(Instant now) {
        attempts = 0;
        lastError = null;
        state = State.PENDING;
        nextAttemptAt = now;
    }

    NotificationDelivery delivery() {
        return new NotificationDelivery(id, alertId, eventKind, state, attempts, nextAttemptAt, lastError, createdAt, sentAt);
    }
}
