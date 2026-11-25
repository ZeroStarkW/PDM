package com.pdm.mvp.analytics;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 频谱基线带状态：有行即该谱型参考带已冻结。 */
public interface SpectrumBandStateRepository extends JpaRepository<SpectrumBandStateEntity, Long> {
    List<SpectrumBandStateEntity> findByPoint_IdAndKindOrderByBandIndex(Long pointId, String kind);

    boolean existsByPoint_IdAndKind(Long pointId, String kind);

    void deleteByPoint_Id(Long pointId);
}
