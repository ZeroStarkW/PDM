package com.pdm.mvp.analytics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/points/{pointId}/baseline")
public class BaselineController {
    private final BaselineService baselines;
    public BaselineController(BaselineService baselines) { this.baselines = baselines; }

    @GetMapping
    public BaselineService.BaselineState get(@PathVariable Long pointId) { return baselines.get(pointId); }
}
