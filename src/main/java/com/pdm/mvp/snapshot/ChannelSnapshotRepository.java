package com.pdm.mvp.snapshot;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelSnapshotRepository extends JpaRepository<ChannelSnapshotEntity, Long> {

    Optional<ChannelSnapshotEntity> findTopByPoint_IdOrderBySampledAtDesc(Long pointId);

    /** 幂等重放查找：同测点同采样时刻的最新一行（V16 唯一约束 uk_channel_snapshot_point_time 的读侧配套）。 */
    Optional<ChannelSnapshotEntity> findByPoint_IdAndSampledAtOrderByIdDesc(Long pointId, Instant sampledAt);
}
