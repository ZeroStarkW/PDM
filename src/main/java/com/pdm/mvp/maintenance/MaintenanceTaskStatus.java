package com.pdm.mvp.maintenance;

public enum MaintenanceTaskStatus {
    OPEN, IN_PROGRESS, COMPLETED, CANCELLED;

    boolean isActive() {
        return this == OPEN || this == IN_PROGRESS;
    }
}
