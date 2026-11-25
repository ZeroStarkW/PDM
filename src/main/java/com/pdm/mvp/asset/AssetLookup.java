package com.pdm.mvp.asset;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AssetLookup {
    private final PointRepository points;
    public AssetLookup(PointRepository points) { this.points = points; }

    public PointEntity requirePoint(String factoryId, String deviceCode, String pointCode) {
        return points.findForTelemetryUpdate(factoryId, deviceCode, pointCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "测点不存在"));
    }

    /** 协议 2.0.0：仅凭 设备编码+测点编码 定位（要求全局唯一，跨工厂重码拒绝）。 */
    public PointEntity requirePointByDeviceAndPoint(String deviceCode, String pointCode) {
        List<PointEntity> matches = points.findByDevice_DeviceCodeAndPointCodeOrderByIdAsc(deviceCode, pointCode);
        if (matches.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "测点不存在");
        if (matches.size() > 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备+测点编码不唯一，无法定位: " + deviceCode + "/" + pointCode);
        return matches.getFirst();
    }

    /**
     * v2 通道定位：{code} 为 ≤18 位正整数按测点 ID（超长数字串按编码处理，防 Long 解析溢出 500），
     * 否则按测点编码全局精确定位（编码入库统一大写，比对同样大写；跨工厂重码拒绝）。
     */
    public PointEntity resolvePoint(String code) {
        String normalized = code == null ? null : code.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized != null && normalized.matches("\\d+") && normalized.length() <= 18) {
            long id = Long.parseLong(normalized);
            if (id > 0) return points.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "测点不存在"));
        }
        List<PointEntity> matches = points.findByPointCodeOrderByIdAsc(normalized);
        if (matches.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "测点不存在");
        if (matches.size() > 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "测点编码不唯一，无法定位: " + code);
        return matches.getFirst();
    }
}
