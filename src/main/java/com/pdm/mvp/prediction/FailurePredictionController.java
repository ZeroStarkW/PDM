package com.pdm.mvp.prediction;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices/{deviceId}/failure-prediction")
public class FailurePredictionController {
    private final FailurePredictionService predictions;

    public FailurePredictionController(FailurePredictionService predictions) {
        this.predictions = predictions;
    }

    @GetMapping
    public FailurePrediction get(@PathVariable Long deviceId) {
        return predictions.estimate(deviceId);
    }
}
