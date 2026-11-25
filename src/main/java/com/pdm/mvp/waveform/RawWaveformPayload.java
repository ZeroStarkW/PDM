package com.pdm.mvp.waveform;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * PHM 协议 2.0.0 原始波形快照载荷（文档 §2.2）：主题 devices/{asset_id}/{point_id}/raw_waveform。
 * preview_waveform 为边缘回传的波形点阵；raw_file_url 为边缘侧对象存储引用（可为空）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RawWaveformPayload(
        @JsonProperty("protocol_version") String protocolVersion,
        @NotNull Long timestamp,
        @JsonProperty("asset_id") @NotBlank String assetId,
        @JsonProperty("point_id") @NotBlank String pointId,
        @JsonProperty("trigger_type") @NotBlank String triggerType,
        @JsonProperty("sampling_rate") @NotNull Double samplingRate,
        @JsonProperty("points_count") Integer pointsCount,
        String unit,
        @JsonProperty("storage_type") String storageType,
        @JsonProperty("raw_file_url") String rawFileUrl,
        @JsonProperty("preview_waveform") List<Double> previewWaveform) {

    public static final String SUPPORTED_PREFIX = "2.";

    public boolean isSupportedProtocol() {
        return protocolVersion != null && protocolVersion.startsWith(SUPPORTED_PREFIX);
    }

    public boolean isKnownTrigger() {
        return WaveformSnapshotEntity.TRIGGER_THRESHOLD.equals(triggerType)
                || WaveformSnapshotEntity.TRIGGER_SCHEDULED.equals(triggerType)
                || WaveformSnapshotEntity.TRIGGER_ON_DEMAND.equals(triggerType);
    }
}
