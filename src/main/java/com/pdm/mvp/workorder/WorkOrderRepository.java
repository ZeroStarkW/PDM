package com.pdm.mvp.workorder;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 继承 {@link JpaSpecificationExecutor} 支持服务端多维筛选分页（GET /api/v1/work-orders/page）。 */
public interface WorkOrderRepository extends JpaRepository<WorkOrderEntity, Long>, JpaSpecificationExecutor<WorkOrderEntity> {
    List<WorkOrderEntity> findTop200ByOrderByCreatedAtDesc();
    /**
     * 返回 empty 有两种语义：工单不存在 或 手工工单无关联告警（alert_id IS NULL）。
     * 调用方需再用 existsById(id) / findByIdForUpdate(id) 区分“工单不存在”（404）与“独立手工工单”。
     */
    @Query("select w.alert.id from WorkOrderEntity w where w.id = :id")
    Optional<Long> findAlertId(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkOrderEntity w where w.id = :id")
    Optional<WorkOrderEntity> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkOrderEntity w where w.alert.id = :alertId")
    Optional<WorkOrderEntity> findByAlert_IdForUpdate(@Param("alertId") Long alertId);

    long countByStatusIn(Collection<String> statuses);
}
