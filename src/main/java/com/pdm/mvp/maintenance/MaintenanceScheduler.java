package com.pdm.mvp.maintenance;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceScheduler {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceScheduler.class);

    private final MaintenancePlanRepository plans;
    private final MaintenanceService maintenance;

    public MaintenanceScheduler(MaintenancePlanRepository plans, MaintenanceService maintenance) {
        this.plans = plans;
        this.maintenance = maintenance;
    }

    @Scheduled(fixedDelayString = "${pdm.maintenance.poll-interval:PT1M}", scheduler = "maintenanceTaskScheduler")
    public void materializeDueOccurrences() {
        Instant now = Instant.now();
        for (Long planId : plans.findDuePlanIds(now)) {
            try {
                maintenance.materializeDueOccurrences(planId, now);
            } catch (RuntimeException failure) {
                log.error("Could not materialize maintenance tasks for plan {}", planId, failure);
            }
        }
    }
}
