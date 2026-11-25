package com.pdm.mvp.bearing;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BearingLibraryRepository extends JpaRepository<BearingLibraryEntity, Long> {
    Optional<BearingLibraryEntity> findByModelNameIgnoreCase(String modelName);

    /** 型号模糊搜索（如输入 6208 命中 SKF/NSK/FAG 各家），按型号名升序限量返回。 */
    List<BearingLibraryEntity> findTop20ByModelNameContainingIgnoreCaseOrderByModelNameAsc(String keyword);

    List<BearingLibraryEntity> findTop50ByOrderByModelNameAsc();

    default List<BearingLibraryEntity> search(String keyword) {
        return keyword == null || keyword.isBlank()
                ? findTop50ByOrderByModelNameAsc()
                : findTop20ByModelNameContainingIgnoreCaseOrderByModelNameAsc(keyword.trim());
    }
}
