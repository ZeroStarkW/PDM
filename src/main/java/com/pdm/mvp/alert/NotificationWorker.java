package com.pdm.mvp.alert;

import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationWorker {
    private final NotificationService notifications;

    public NotificationWorker(NotificationService notifications) { this.notifications = notifications; }

    @Scheduled(fixedDelayString = "${pdm.notifications.poll-interval:PT5S}", scheduler = "notificationTaskScheduler")
    public void poll() {
        notifications.enqueueEscalations();
        // Bound each delivery batch so the next poll can check for new escalations.
        long started = System.nanoTime();
        for (int processed = 0; processed < 20; processed++) {
            if (!notifications.deliverNext() || System.nanoTime() - started >= Duration.ofSeconds(4).toNanos()) break;
        }
    }
}
