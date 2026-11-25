package com.pdm.mvp.bearing;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BearingLibraryService {
    private final BearingLibraryRepository bearings;

    public BearingLibraryService(BearingLibraryRepository bearings) { this.bearings = bearings; }

    /** 型号模糊搜索（keyword 如 6208），空关键字返回全量（限量）。 */
    @Transactional(readOnly = true)
    public List<BearingModelView> search(String keyword) {
        return bearings.search(keyword).stream().map(BearingModelView::from).toList();
    }

    @Transactional(readOnly = true)
    public BearingLibraryEntity require(Long id) {
        if (id == null) return null;
        return bearings.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "轴承型号不存在"));
    }

    public record BearingModelView(Long id, String modelName, String manufacturer, int ballCount,
            double ballDiameter, double pitchDiameter, double contactAngleDeg,
            double bpfoFactor, double bpfiFactor, double bsfFactor, double ftfFactor) {
        static BearingModelView from(BearingLibraryEntity entity) {
            return new BearingModelView(entity.getId(), entity.getModelName(), entity.getManufacturer(),
                    entity.getBallCount(), entity.getBallDiameter().doubleValue(),
                    entity.getPitchDiameter().doubleValue(), entity.getContactAngleDeg().doubleValue(),
                    entity.getBpfoFactor().doubleValue(), entity.getBpfiFactor().doubleValue(),
                    entity.getBsfFactor().doubleValue(), entity.getFtfFactor().doubleValue());
        }
    }
}
