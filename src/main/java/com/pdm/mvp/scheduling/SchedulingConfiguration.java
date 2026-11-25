package com.pdm.mvp.scheduling;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {
    @Bean
    TaskScheduler maintenanceTaskScheduler() {
        return singleThreadScheduler("pdm-maintenance-");
    }

    @Bean
    TaskScheduler notificationTaskScheduler() {
        return singleThreadScheduler("pdm-notification-");
    }

    private static ThreadPoolTaskScheduler singleThreadScheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        return scheduler;
    }
}
