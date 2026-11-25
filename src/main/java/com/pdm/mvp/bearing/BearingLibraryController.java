package com.pdm.mvp.bearing;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 标准轴承字典查询：GET /api/v1/bearings/models?keyword=6208 */
@RestController
@RequestMapping("/api/v1/bearings")
public class BearingLibraryController {
    private final BearingLibraryService bearings;

    public BearingLibraryController(BearingLibraryService bearings) { this.bearings = bearings; }

    @GetMapping("/models")
    public List<BearingLibraryService.BearingModelView> models(@RequestParam(required = false) String keyword) {
        return bearings.search(keyword);
    }
}
