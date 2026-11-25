package com.pdm.mvp.telemetry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelemetryRequest(
        @NotNull Long timestamp,
        @JsonProperty("factory_id") @NotBlank String factoryId,
        @JsonProperty("device_code") @NotBlank String deviceCode,
        @JsonProperty("point_code") @NotBlank String pointCode,
        @JsonProperty("data_quality") @NotNull Integer dataQuality,
        @NotNull @Valid TelemetryValues telemetry,
        @JsonProperty("top_peaks") List<TopPeak> topPeaks) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TelemetryValues(
            @JsonProperty("v_rms") @NotNull Double vRms,
            @JsonProperty("a_peak") @NotNull Double aPeak,
            @JsonProperty("a_kurtosis") @NotNull Double aKurtosis,
            @JsonProperty("freq_1x_amp") @NotNull Double freq1xAmp,
            @JsonProperty("freq_2x_amp") @NotNull Double freq2xAmp,
            @JsonProperty("envelope_rms") Double envelopeRms,
            @JsonProperty("envelope_bpfo_amp") Double envelopeBpfoAmp,
            Double rpm,
            Double temperature,
            @JsonProperty("health_index") Double simulatorHealthIndex) {}

    public record TopPeak(Double frequency, Double amplitude, Double snr) {}
}
