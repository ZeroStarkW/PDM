package com.pdm.mvp.maintenance;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenanceTaskRepository extends JpaRepository<MaintenanceTaskEntity, Long> {
    @EntityGraph(attributePaths = "device")
    Page<MaintenanceTaskEntity> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "device")
    @Query("""
            select t from MaintenanceTaskEntity t
            where t.status in (com.pdm.mvp.maintenance.MaintenanceTaskStatus.OPEN,
                               com.pdm.mvp.maintenance.MaintenanceTaskStatus.IN_PROGRESS)
            """)
    Page<MaintenanceTaskEntity> findActive(Pageable pageable);

    @EntityGraph(attributePaths = "device")
    @Query("""
            select t from MaintenanceTaskEntity t
            where t.status in (com.pdm.mvp.maintenance.MaintenanceTaskStatus.COMPLETED,
                               com.pdm.mvp.maintenance.MaintenanceTaskStatus.CANCELLED)
            """)
    Page<MaintenanceTaskEntity> findHistory(Pageable pageable);

    boolean existsByPlan_IdAndDueAt(Long planId, Instant dueAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MaintenanceTaskEntity t where t.id = :id")
    Optional<MaintenanceTaskEntity> findByIdForUpdate(@Param("id") Long id);
}
