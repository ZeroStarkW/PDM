package com.pdm.mvp.maintenance;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaintenancePlanRepository extends JpaRepository<MaintenancePlanEntity, Long> {
    @EntityGraph(attributePaths = "device")
    Page<MaintenancePlanEntity> findAllByOrderByNextDueAtAscIdAsc(Pageable pageable);

    @Query("select p.id from MaintenancePlanEntity p where p.enabled = true and p.nextDueAt <= :now order by p.nextDueAt, p.id")
    List<Long> findDuePlanIds(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from MaintenancePlanEntity p where p.id = :id")
    Optional<MaintenancePlanEntity> findByIdForUpdate(@Param("id") Long id);
}
