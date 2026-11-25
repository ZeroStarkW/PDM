package com.pdm.mvp.telemetry;

import com.pdm.mvp.modality.ModalityPlugin;
import com.pdm.mvp.modality.ModalityTypes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Component
public class TelemetryValidator {
    private static final long MAX_CLOCK_SKEW_MILLIS = Duration.ofMinutes(5).toMillis();
    /** 特征编码命名规则（同时是时序库列名与基线特征编码）：小写字母开头，仅小写字母/数字/下划线，最长 64。 */
    static final java.util.regex.Pattern FEATURE_KEY = java.util.regex.Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    /** 平台自有标签/字段名，特征编码不得占用：同名会覆盖平台值或引发列类型冲突，使整批写入被时序库拒绝。 */
    static final java.util.Set<String> RESERVED_FEATURE_KEYS = java.util.Set.of("time", "factory_id", "device_code",
            "point_code", "data_quality", "status_code", "status", "modality", "health_index", "raw_snapshot_ref",
            "top_peaks_json");

    private final Clock clock;
    public TelemetryValidator() { this(Clock.systemUTC()); }
    TelemetryValidator(Clock clock) { this.clock = clock; }

    public void validate(TelemetryRequest request) {
        if (request == null || request.timestamp() == null || request.dataQuality() == null || request.telemetry() == null)
            throw bad("timestamp、data_quality 和 telemetry 为必填字段");
        long skew = Math.abs(Duration.between(Instant.ofEpochMilli(request.timestamp()), clock.instant()).toMillis());
        if (skew > Duration.ofMinutes(5).toMillis()) throw bad("遥测时间戳与服务器时间相差超过 5 分钟");
        if (request.dataQuality() < 0 || request.dataQuality() > 3) throw bad("data_quality 只能是 0 到 3");
        var values = request.telemetry();
        if (values.vRms() == null || values.aPeak() == null || values.aKurtosis() == null
                || values.freq1xAmp() == null || values.freq2xAmp() == null) throw bad("核心振动特征字段不能为空");
        if (!finiteNonNegative(values.vRms(), values.aPeak(), values.freq1xAmp(), values.freq2xAmp()))
            throw bad("振动幅值必须为有限且非负数值");
        if (!Double.isFinite(values.aKurtosis()) || values.aKurtosis() < 0) throw bad("a_kurtosis 必须为有限非负数值");
        if (!optionalFiniteNonNegative(values.envelopeRms()) || !optionalFiniteNonNegative(values.envelopeBpfoAmp())
                || !optionalFiniteNonNegative(values.rpm()) || !optionalFinite(values.temperature()))
            throw bad("可选特征必须为有限数值，转速和幅值不能为负数");
        validateTopPeaks(request.topPeaks());
    }

    /**
     * 统一契约校验：版本、模态、公共约束、特征编码命名与有限性、必填项，特征域校验委托插件。
     * 时钟偏差按方向区分：超前服务器时间一律只容忍 5 分钟；滞后超过 5 分钟时，仅当插件声明了更大的
     * {@link ModalityPlugin#maxPastDelay()} 才接受（事件型模态的断网补传事件）。
     */
    public void validate(TelemetryEnvelope envelope, ModalityPlugin plugin) {
        if (envelope.modalityType() == null || envelope.modalityType().isBlank()) throw bad("modality_type 为必填字段");
        if (envelope.contractVersion() != ModalityTypes.SUPPORTED_CONTRACT_VERSION)
            throw bad("不支持的遥测契约版本: " + envelope.contractVersion());
        // age > 0：样本时间早于服务器（滞后）；age < 0：样本时间超前服务器
        long ageMillis = Duration.between(envelope.timestampInstant(), clock.instant()).toMillis();
        if (ageMillis < -MAX_CLOCK_SKEW_MILLIS) throw bad("遥测时间戳与服务器时间相差超过 5 分钟");
        Duration maxPastDelay = plugin.maxPastDelay();
        if (ageMillis > MAX_CLOCK_SKEW_MILLIS && (maxPastDelay == null || ageMillis > maxPastDelay.toMillis())) {
            throw bad(maxPastDelay == null || !maxPastDelay.isPositive()
                    ? "遥测时间戳与服务器时间相差超过 5 分钟"
                    : "遥测时间戳滞后服务器时间超过允许的最大延迟 " + maxPastDelay);
        }
        if (envelope.dataQuality() < 0 || envelope.dataQuality() > 3) throw bad("data_quality 只能是 0 到 3");
        if (envelope.features() == null || envelope.features().isEmpty())
            throw bad("metrics/features 特征集合不能为空");
        for (var entry : envelope.features().entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null
                    || !Double.isFinite(entry.getValue()))
                throw bad("特征编码与数值必须有效，特征值必须为有限数值");
        }
        for (String key : envelope.features().keySet()) {
            if (RESERVED_FEATURE_KEYS.contains(key)) throw bad("特征编码不能使用平台保留字段: " + key);
            if (!FEATURE_KEY.matcher(key).matches())
                throw bad("特征编码 " + key + " 非法: 须匹配 ^[a-z][a-z0-9_]{0,63}$");
        }
        List<String> missing = plugin.requiredFeatures().stream().filter(code -> !envelope.hasFeature(code)).toList();
        if (!missing.isEmpty()) throw bad("缺少模态必需特征: " + String.join(", ", missing));
        plugin.validateFeatures(envelope);
        validateTopPeaks(envelope.topPeaks());
    }

    private void validateTopPeaks(List<TelemetryRequest.TopPeak> topPeaks) {
        if (topPeaks == null) return;
        if (topPeaks.size() > 10) throw bad("top_peaks 最多 10 项");
        if (topPeaks.stream().anyMatch(peak -> peak == null
                || peak.frequency() == null || peak.amplitude() == null
                || !Double.isFinite(peak.frequency()) || !Double.isFinite(peak.amplitude())
                || peak.frequency() < 0 || peak.amplitude() < 0
                || peak.snr() != null && (!Double.isFinite(peak.snr()) || peak.snr() < 0)))
            throw bad("top_peaks 中的频率、幅值和信噪比必须为有效非负数值");
    }

    private static boolean finiteNonNegative(double... values) {
        for (double value : values) if (!Double.isFinite(value) || value < 0) return false;
        return true;
    }
    private static boolean optionalFinite(Double value) { return value == null || Double.isFinite(value); }
    private static boolean optionalFiniteNonNegative(Double value) { return value == null || Double.isFinite(value) && value >= 0; }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
