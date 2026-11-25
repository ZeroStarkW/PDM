package com.pdm.mvp.analytics;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 频谱基线带学习样本：滚动窗口读写与清理（与标量基线样本同一模式）。 */
public interface SpectrumBandSampleRepository extends JpaRepository<SpectrumBandSampleEntity, Long> {
    boolean existsByPoint_IdAndKindAndSampledAt(Long pointId, String kind, Instant sampledAt);

    List<SpectrumBandSampleEntity> findByPoint_IdAndKindAndSampledAtBetweenOrderBySampledAtAsc(
            Long pointId, String kind, Instant from, Instant to);

    void deleteByPoint_IdAndKindAndSampledAtBefore(Long pointId, String kind, Instant cutoff);

    long countByPoint_IdAndKind(Long pointId, String kind);

    void deleteByPoint_Id(Long pointId);
}
