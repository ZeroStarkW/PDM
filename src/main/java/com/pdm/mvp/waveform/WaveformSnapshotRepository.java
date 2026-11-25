package com.pdm.mvp.waveform;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WaveformSnapshotRepository extends JpaRepository<WaveformSnapshotEntity, Long> {

    Optional<WaveformSnapshotEntity> findTopByPoint_IdOrderBySampledAtDesc(Long pointId);

    /** MQTT QoS1 重投去重：同 (point_id, sampled_at) 的快照唯一。 */
    Optional<WaveformSnapshotEntity> findByPoint_IdAndSampledAt(Long pointId, java.time.Instant sampledAt);
}
