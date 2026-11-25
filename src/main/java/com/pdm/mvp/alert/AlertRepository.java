package com.pdm.mvp.alert;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** 继承 {@link JpaSpecificationExecutor} 支持服务端多维筛选分页（GET /api/v1/alerts/page）。 */
public interface AlertRepository extends JpaRepository<AlertEntity, Long>, JpaSpecificationExecutor<AlertEntity> {
    List<AlertEntity> findTop200ByOrderByCreatedAtDesc();

    /** 测点最新未闭环告警（波形越限触发时关联 raw_snapshot_url）。 */
    AlertEntity findTopByPoint_IdAndStatusLessThanOrderByCreatedAtDesc(Long pointId, short status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from AlertEntity a
            where a.device.id = :deviceId and a.alertLevel = :level
                and ((a.status in (0, 1) and a.createdAt > :since)
                    or (a.status = 3 and a.suppressedAt > :since))
                and ((a.diagnosticDeviceTypeId = :typeId
                        and a.diagnosticRuleId is not distinct from :ruleId
                        and a.diagnosticRuleVersion is not distinct from :ruleVersion)
                    or (a.status = 3 and a.diagnosticDeviceTypeId is null))
            order by case when a.status = 3 then 0 else 1 end, a.createdAt desc, a.id desc
            """)
    List<AlertEntity> findOpenOrSuppressed(@Param("deviceId") Long deviceId, @Param("level") String level,
            @Param("since") Instant since, @Param("typeId") Long typeId, @Param("ruleId") Long ruleId,
            @Param("ruleVersion") Long ruleVersion, Pageable limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AlertEntity a where a.id = :id")
    Optional<AlertEntity> findByIdForUpdate(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("update AlertEntity a set a.notified = true where a.id = :id")
    void markNotified(@Param("id") Long alertId);

    long countByStatusNot(short closedStatus);
    long countByStatusIn(Collection<Short> statuses);

    @Query("select count(distinct a.device.id) from AlertEntity a where a.status in :statuses")
    long countDistinctDevicesByStatusIn(@Param("statuses") Collection<Short> statuses);
}
