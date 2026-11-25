package com.pdm.mvp.telemetry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 遥测样本存储/查询 DTO。振动模态沿用固定特征字段；其他模态特征经 {@code features} 携带。
 */
public record TelemetryPoint(Instant timestamp, String factoryCode, String deviceCode, String pointCode,
        String modalityType, Double vRms, Double aPeak, Double aKurtosis, Double freq1xAmp, Double freq2xAmp,
        Double envelopeRms, Double envelopeBpfoAmp, Double temperature, Double rpm, Double healthIndex,
        int dataQuality, boolean stopped, int statusCode, String status, List<TelemetryRequest.TopPeak> topPeaks,
        Map<String, Double> features, String rawSnapshotRef) {

    public boolean isVibration() {
        return modalityType == null || com.pdm.mvp.modality.ModalityTypes.ROTATING_VIB.equals(modalityType);
    }
}
