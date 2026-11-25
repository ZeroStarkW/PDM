package com.pdm.mvp.alert;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<NotificationEvent, Long> {
    String INSERT_EVENTS = """
            insert into pdm_notification_outbox (alert_id, event_kind, payload)
            select a.id, a.event_kind, jsonb_build_object(
                'alertId', a.id, 'factoryId', f.factory_code, 'deviceCode', d.device_code, 'pointCode', p.point_code,
                'level', a.alert_level, 'healthIndex', a.health_index, 'dominantFactor', a.dominant_factor,
                'triggerAmplitude', a.trigger_amp, 'baselineAmplitude', a.base_amp)
            from candidates a
            join pdm_device d on d.id = a.device_id
            join pdm_factory f on f.id = d.factory_id
            join pdm_measurement_point p on p.id = a.point_id
            on conflict (alert_id, event_kind) do nothing
            """;

    @Modifying
    @Query(value = """
            with candidates as (select a.*, 'ALERT' as event_kind from pdm_alert_event a where a.id = :alertId)
            """ + INSERT_EVENTS, nativeQuery = true)
    void enqueueAlert(@Param("alertId") Long alertId);

    @Modifying
    @Query(value = """
            with candidates as (
                select a.*, 'ESCALATION' as event_kind from pdm_alert_event a
                where a.status = 0 and a.created_at <= :cutoff and not exists (
                    select 1 from pdm_notification_outbox n where n.alert_id = a.id and n.event_kind = 'ESCALATION')
                order by a.created_at, a.id limit 100 for update of a skip locked
            )
            """ + INSERT_EVENTS, nativeQuery = true)
    int enqueueEscalations(@Param("cutoff") Instant cutoff);

    @Query(value = """
            select * from pdm_notification_outbox where state = 'PENDING' and next_attempt_at <= CURRENT_TIMESTAMP
            order by next_attempt_at, id limit 1 for update skip locked
            """, nativeQuery = true)
    Optional<NotificationEvent> lockNextDue();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from NotificationEvent n where n.id = :id")
    Optional<NotificationEvent> lockById(@Param("id") Long id);

    @Query("""
            select new com.pdm.mvp.alert.NotificationDelivery(n.id, n.alertId, n.eventKind, n.state, n.attempts,
                n.nextAttemptAt, n.lastError, n.createdAt, n.sentAt)
            from NotificationEvent n where n.alertId in :ids order by n.alertId, n.id
            """)
    List<NotificationDelivery> deliveries(@Param("ids") Collection<Long> ids);

    Page<NotificationDelivery> findByStateOrderByIdDesc(NotificationEvent.State state, Pageable pageable);
}
