package com.pdm.mvp.timeseries;

import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.Point;
import com.influxdb.v3.client.PointValues;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.telemetry.TelemetryPoint;
import com.pdm.mvp.telemetry.TelemetryRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class InfluxTelemetryStore implements TelemetryStore, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(InfluxTelemetryStore.class);
    /** 振动行已有固定列名（含哨兵值语义），扩展特征写入时跳过。 */
    private static final java.util.Set<String> LEGACY_VIBRATION_FIELDS = java.util.Set.of(
            "v_rms", "a_peak", "a_kurtosis", "freq_1x_amp", "freq_2x_amp",
            "envelope_rms", "envelope_bpfo_amp", "temperature", "rpm",
            "data_quality", "status_code", "status", "modality", "health_index");
    /** a_peak 缺省哨兵：协议 2.1.0 旋转振动样本可不带 a_peak，写哨兵使该列对每一振动行都保持 double，读回时置空。 */
    private static final double A_PEAK_SENTINEL = -1.0;
    private static final com.fasterxml.jackson.databind.ObjectMapper TOP_PEAKS_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final InfluxDBClient client;
    private final BlockingQueue<TelemetryPoint> queue = new ArrayBlockingQueue<>(2500);
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Thread writer;

    public InfluxTelemetryStore(InfluxDBClient client) {
        this.client = client;
        this.writer = Thread.ofPlatform().name("pdm-influx-writer").daemon(true).start(this::writeLoop);
    }

    @Override
    public void write(TelemetryPoint point) {
        if (!accepting.get()) throw new IllegalStateException("InfluxDB writer is shutting down");
        if (!queue.offer(point)) writeBatch(List.of(point));
    }

    private void writeLoop() {
        while (accepting.get() || !queue.isEmpty()) {
            try {
                TelemetryPoint first = queue.poll(250, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                List<TelemetryPoint> batch = new ArrayList<>(100);
                batch.add(first);
                queue.drainTo(batch, 99);
                writeBatch(batch);
            } catch (InterruptedException exception) {
                // Recheck the accepting flag and queue without terminating the drain loop.
            }
        }
    }

    private void writeBatch(List<TelemetryPoint> samples) {
        List<Point> points = samples.stream().map(this::toPoint).toList();
        for (int attempt = 1; attempt <= 3; attempt++) {
            try { client.writePoints(points); return; }
            catch (Exception exception) {
                if (attempt == 3) {
                    log.error("InfluxDB batch write failed after {} attempts ({} points)", attempt, points.size(), exception);
                } else {
                    try { Thread.sleep(100L * (1L << (attempt - 1))); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            }
        }
    }

    /** 包可见便于单测映射：特征字段先写，平台自有字段最后写（特征码同名不可能覆盖其值或类型）。 */
    Point toPoint(TelemetryPoint sample) {
        boolean vibration = sample.isVibration();
        Point point = Point.measurement("point_telemetry")
                .setTag("factory_id", sample.factoryCode())
                .setTag("device_code", sample.deviceCode())
                .setTag("point_code", sample.pointCode())
                .setTimestamp(sample.timestamp());
        // 1) 特征字段先写：振动行跳过已有固定列名的既有字段（含哨兵值语义），扩展特征按需附加；
        //    其他模态特征码 → 独立字段
        if (sample.features() != null) {
            for (var entry : sample.features().entrySet()) {
                if (vibration && LEGACY_VIBRATION_FIELDS.contains(entry.getKey())) continue;
                point.setField(entry.getKey(), entry.getValue());
            }
        }
        if (vibration) {
            // 振动行保持历史字段完全不变；a_peak 可缺省（协议 2.1.0），缺省写哨兵 -1.0 使该列恒为 double
            point.setField("v_rms", sample.vRms())
                    .setField("a_peak", sample.aPeak() == null ? A_PEAK_SENTINEL : sample.aPeak())
                    .setField("a_kurtosis", sample.aKurtosis())
                    .setField("freq_1x_amp", sample.freq1xAmp())
                    .setField("freq_2x_amp", sample.freq2xAmp());
            point.setField("envelope_rms", sample.envelopeRms() == null ? -1.0 : sample.envelopeRms());
            point.setField("envelope_bpfo_amp", sample.envelopeBpfoAmp() == null ? -1.0 : sample.envelopeBpfoAmp());
            point.setField("temperature", sample.temperature() == null ? -999.0 : sample.temperature());
            point.setField("rpm", sample.rpm() == null ? -1.0 : sample.rpm());
        } else if (sample.rawSnapshotRef() != null && !sample.rawSnapshotRef().isBlank()) {
            // raw_snapshot_ref 仅作元数据存储
            point.setField("raw_snapshot_ref", sample.rawSnapshotRef());
        }
        point.setField("health_index", sample.healthIndex() == null ? -1.0 : sample.healthIndex());
        // 2) 平台自有字段最后写：即使特征码与之同名，也以平台值为准
        point.setField("data_quality", sample.dataQuality())
                .setField("status_code", sample.statusCode())
                .setField("status", sample.status())
                .setField("modality", vibration ? "ROTATING_VIB" : sample.modalityType());
        if (sample.topPeaks() != null && !sample.topPeaks().isEmpty()) {
            try { point.setField("top_peaks_json", TOP_PEAKS_MAPPER.writeValueAsString(sample.topPeaks())); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) { log.warn("Could not encode top_peaks", ex); }
        }
        return point;
    }

    @Override
    public List<TelemetryPoint> query(PointEntity point, Instant from, Instant to, String interval) {
        return doQuery(point, from, to, interval, List.of());
    }

    @Override
    public List<TelemetryPoint> queryFeatures(PointEntity point, Instant from, Instant to, String interval,
            List<String> extraFeatureCodes) {
        return doQuery(point, from, to, interval, extraFeatureCodes == null ? List.of() : extraFeatureCodes);
    }

    /** 振动基础聚合列（可按列剔除）：从未写过振动数据的部署表里没有这些列，逐列降级而不是整体 500。 */
    static final java.util.List<String> BASE_VIBRATION_COLUMNS = java.util.List.of("v_rms", "a_peak",
            "a_kurtosis", "freq_1x_amp", "freq_2x_amp", "envelope_rms", "envelope_bpfo_amp", "rpm", "temperature");
    /** 基础列聚合片段（与既有 SQL 文案逐字一致，仅允许整段剔除）。 */
    private static final java.util.Map<String, String> BASE_AGGREGATE_FRAGMENTS = java.util.Map.of(
            "v_rms", ", AVG(v_rms) AS v_rms",
            "a_peak", ", MAX(NULLIF(a_peak, -1.0)) AS a_peak",
            "a_kurtosis", ", AVG(a_kurtosis) AS a_kurtosis",
            "freq_1x_amp", ", AVG(freq_1x_amp) AS freq_1x_amp",
            "freq_2x_amp", ", AVG(freq_2x_amp) AS freq_2x_amp",
            "envelope_rms", ", AVG(NULLIF(envelope_rms, -1.0)) AS envelope_rms",
            "envelope_bpfo_amp", ", AVG(NULLIF(envelope_bpfo_amp, -1.0)) AS envelope_bpfo_amp",
            "rpm", ", AVG(NULLIF(rpm, -1.0)) AS rpm",
            "temperature", ", AVG(NULLIF(temperature, -999.0)) AS temperature");

    /**
     * 逐列降级重试：基础振动列缺失时只剔除缺失的那几列（报错随附现存列清单则一次剔除全部缺失列），
     * 扩展特征列缺失时同样只剔除缺失列（不再整体退化到零扩展特征）；每次重试至少剔除一列保证终止。
     */
    private List<TelemetryPoint> doQuery(PointEntity point, Instant from, Instant to, String interval,
            List<String> extraFeatureCodes) {
        java.util.LinkedHashSet<String> base = new java.util.LinkedHashSet<>(BASE_VIBRATION_COLUMNS);
        java.util.List<String> extras = new java.util.ArrayList<>(extraFeatureCodes);
        while (true) {
            try {
                return doQueryOnce(point, from, to, interval, base, extras);
            } catch (RuntimeException exception) {
                List<String> remainingBase = withoutMissingColumns(new java.util.ArrayList<>(base), exception);
                if (remainingBase != null) {
                    log.debug("Base vibration columns missing for point {}; retrying with {} of {} base columns",
                            point.getId(), remainingBase.size(), base.size());
                    base = new java.util.LinkedHashSet<>(remainingBase);
                    continue;
                }
                List<String> remainingExtras = withoutMissingColumns(extras, exception);
                if (remainingExtras != null) {
                    log.debug("Extended feature columns missing for point {}; retrying with {} of {} feature columns",
                            point.getId(), remainingExtras.size(), extras.size());
                    extras = remainingExtras;
                    continue;
                }
                throw exception;
            }
        }
    }

    private List<TelemetryPoint> doQueryOnce(PointEntity point, Instant from, Instant to, String interval,
            java.util.Set<String> baseColumns, List<String> extraFeatureCodes) {
        if (point.getLastTelemetryAt() == null) return List.of();
        String sqlInterval = java.util.Map.of("1m", "1 minute", "5m", "5 minutes", "15m", "15 minutes",
                "1h", "1 hour", "6h", "6 hours").get(interval);
        if (sqlInterval == null) throw new IllegalArgumentException("Unsupported telemetry aggregation interval");
        String factory = sqlQuote(point.getDevice().getFactory().getFactoryCode());
        String device = sqlQuote(point.getDevice().getDeviceCode());
        String code = sqlQuote(point.getPointCode());
        StringBuilder baseSelect = new StringBuilder();
        for (String column : BASE_VIBRATION_COLUMNS) {
            if (!baseColumns.contains(column)) continue;
            baseSelect.append(BASE_AGGREGATE_FRAGMENTS.get(column));
        }
        // 扩展特征（协议 2.0.0 附加字段）按需聚合，哨兵 -1 置空
        StringBuilder extraSelect = new StringBuilder();
        for (String extra : extraFeatureCodes) extraSelect.append(", AVG(NULLIF(").append(sqlQuote(extra))
                .append(", -1.0)) AS ").append(sqlQuote(extra));
        String sql = "SELECT date_bin(INTERVAL '" + sqlInterval + "', time) AS time"
                + baseSelect
                + ", AVG(NULLIF(health_index, -1.0)) AS health_index, MAX(data_quality) AS data_quality, "
                + "MAX(CASE WHEN status_code BETWEEN 1 AND 3 OR (status_code = 0 AND health_index >= 0) "
                + "THEN status_code END) AS health_status, "
                + "MAX(CASE WHEN status_code = 5 OR (status_code = 4 AND data_quality = 0) "
                + "THEN 1 ELSE 0 END) AS stopped "
                + extraSelect
                + " FROM point_telemetry WHERE factory_id='" + factory + "' AND device_code='" + device
                + "' AND point_code='" + code + "' AND time >= TIMESTAMP '" + from
                + "' AND time <= TIMESTAMP '" + to + "' GROUP BY 1 ORDER BY time DESC LIMIT " + AGGREGATED_ROW_LIMIT;
        try (Stream<PointValues> rows = client.queryPoints(sql)) {
            List<TelemetryPoint> result = new ArrayList<>();
            rows.forEach(row -> {
                int qualityCode = (int) number(row, "data_quality");
                Number healthStatus = row.getField("health_status") instanceof Number value ? value : null;
                boolean stopped = number(row, "stopped") > 0;
                int statusCode = aggregateStatusCode(healthStatus, qualityCode, stopped);
                java.util.Map<String, Double> extras = null;
                if (!extraFeatureCodes.isEmpty()) {
                    extras = new java.util.LinkedHashMap<>();
                    for (String extra : extraFeatureCodes) {
                        Double value = nullableNumber(row, extra, -1);
                        if (value != null) extras.put(extra, value);
                    }
                }
                result.add(new TelemetryPoint(toInstant(row.getTimestamp()), factory, device, code,
                    "ROTATING_VIB", number(row, "v_rms"), nullableNumber(row, "a_peak", -1), number(row, "a_kurtosis"),
                    number(row, "freq_1x_amp"), number(row, "freq_2x_amp"), nullableNumber(row, "envelope_rms", -1),
                    nullableNumber(row, "envelope_bpfo_amp", -1), nullableNumber(row, "temperature", -999),
                    nullableNumber(row, "rpm", -1), nullableNumber(row, "health_index", -1),
                    qualityCode, stopped, statusCode, statusName(statusCode, healthStatus != null), List.of(),
                    extras, null));
            });
            return result.reversed();
        } catch (RuntimeException exception) {
            if (isMissingTable(exception)) {
                log.debug("InfluxDB has no telemetry table yet for point {}", point.getId());
                return List.of();
            }
            throw exception;
        }
    }

    private static double number(PointValues row, String field) {
        Object value = row.getField(field);
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }
    private static Double nullableNumber(PointValues row, String field, double sentinel) {
        Object value = row.getField(field);
        if (!(value instanceof Number number)) return null;
        double result = number.doubleValue();
        return result == sentinel ? null : result;
    }
    private static int aggregateStatusCode(Number healthStatus, int qualityCode, boolean stopped) {
        if (healthStatus != null) return healthStatus.intValue();
        if (qualityCode != 0) return 4;
        return stopped ? 5 : 0;
    }

    private static String statusName(int statusCode, boolean hasHealthAssessment) {
        return switch (statusCode) {
            case 1 -> "ATTENTION";
            case 2 -> "WARNING";
            case 3 -> "DANGER";
            case 4 -> "SENSOR_CHECK";
            case 5 -> "STOPPED";
            default -> hasHealthAssessment ? "HEALTHY" : "HEALTHY_OR_BASELINE_LEARNING";
        };
    }
    private static Instant toInstant(Number timestampNanos) {
        long nanos = timestampNanos.longValue();
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
    }
    private static boolean isMissingTable(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("table") && (normalized.contains("not found") || normalized.contains("does not exist"))) return true;
            }
        }
        return false;
    }

    /** InfluxDB 3 Core 对未建出的列报 "No field named x"；DataFusion 另一常见文案为 column "x" not found。 */
    private static boolean isMissingColumn(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("no field named")
                        || normalized.contains("column") && (normalized.contains("not found") || normalized.contains("does not exist"))) return true;
            }
        }
        return false;
    }

    /** 列不存在报错中点名的缺失列（两种文案各一个捕获组）。 */
    private static final java.util.regex.Pattern MISSING_COLUMN_NAME = java.util.regex.Pattern.compile(
            "no field named\\s+\"?([a-z0-9_]+)\"?|column\\s+\"?([a-z0-9_]+)\"?\\s+(?:not found|does not exist)",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    /** InfluxDB 3 Core 报错尾部随附的现存列清单（Valid fields are table.col, …）。 */
    private static final java.util.regex.Pattern VALID_COLUMNS = java.util.regex.Pattern.compile(
            "valid fields are\\s+([a-z0-9_.,\\s]+)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 列不存在时用于重试的特征码：报错随附现存列清单时一次剔除全部缺失列，否则只剔除报错点名的那一列，
     * 点不出列名时退化为不含特征列。非列缺失错误、或请求里没有可剔除的列时返回 null（保证重试终止）。
     */
    static List<String> withoutMissingColumns(List<String> codes, RuntimeException exception) {
        if (codes.isEmpty() || !isMissingColumn(exception)) return null;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) continue;
            java.util.regex.Matcher missing = MISSING_COLUMN_NAME.matcher(message);
            if (!missing.find()) continue;
            String missingColumn = missing.group(1) != null ? missing.group(1) : missing.group(2);
            java.util.Set<String> existing = existingColumns(message);
            List<String> remaining = existing == null
                    ? codes.stream().filter(code -> !code.equals(missingColumn)).toList()
                    : codes.stream().filter(existing::contains).toList();
            return remaining.size() < codes.size() ? remaining : null;
        }
        return List.of();
    }

    /** 报错随附的现存列清单（去掉 table. 前缀）；报错未携带时返回 null。 */
    private static java.util.Set<String> existingColumns(String message) {
        java.util.regex.Matcher matcher = VALID_COLUMNS.matcher(message);
        if (!matcher.find()) return null;
        java.util.Set<String> columns = new java.util.HashSet<>();
        for (String field : matcher.group(1).split(",")) {
            // 清单以句点结尾：先去掉句末句点，再剥掉 table. 前缀（否则末列会被整列吞掉）
            String column = field.strip().replaceAll("\\.$", "").replaceFirst("^.*\\.", "");
            if (!column.isEmpty()) columns.add(column);
        }
        return columns;
    }
    private static final java.util.Map<String, String> AGGREGATION_STEPS = java.util.Map.of("1m", "1 minute",
            "5m", "5 minutes", "15m", "15 minutes", "1h", "1 hour", "6h", "6 hours");
    /** 服务端行数上限：聚合路径 5000 箱、raw 路径 2000 行（超出只保留最新，前端以 truncated 标记呈现）。 */
    public static final int AGGREGATED_ROW_LIMIT = 5000;
    public static final int RAW_ROW_LIMIT = 2000;
    /** 振动行既有列的哨兵值语义（写侧以 -1/-999 表示缺失），聚合时过滤；a_peak 自协议 2.1.0 起可缺省（同用 -1 哨兵）。 */
    private static final java.util.Map<String, Double> VIBRATION_SENTINELS = java.util.Map.of(
            "envelope_rms", -1.0, "envelope_bpfo_amp", -1.0, "rpm", -1.0, "temperature", -999.0, "a_peak", -1.0);

    @Override
    public List<ChannelSample> queryChannel(PointEntity point, Instant from, Instant to, String step,
            List<String> featureCodes) {
        return queryChannel(point, from, to, step, featureCodes, "raw".equals(step) ? RAW_ROW_LIMIT : AGGREGATED_ROW_LIMIT);
    }

    @Override
    public List<ChannelSample> queryChannel(PointEntity point, Instant from, Instant to, String step,
            List<String> featureCodes, int limit) {
        List<String> codes = featureCodes == null ? List.of() : featureCodes;
        while (true) {
            try {
                return "raw".equals(step)
                        ? queryChannelOnce(point, from, to, codes, Math.max(1, limit))
                        : queryChannelAggregated(point, from, to, step, codes, Math.max(1, limit));
            } catch (RuntimeException exception) {
                List<String> remaining = withoutMissingColumns(codes, exception);
                if (remaining == null) throw fileLimitExceeded(exception);
                // 请求的特征列尚未写入（列不存在）：只剔除缺失列后重试，其余特征照常返回
                log.debug("Feature columns missing for channel {}; retrying with {} of {} feature columns",
                        point.getId(), remaining.size(), codes.size());
                codes = remaining;
            }
        }
    }

    /** InfluxDB 3 Core 单次查询扫描的 Parquet 文件数超限（历史范围过大，服务端 --query-file-limit）：
     *  转成可读的 422，而不是笼统的 500。 */
    private static RuntimeException fileLimitExceeded(RuntimeException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("exceeding the file limit")) {
                return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "所选时间范围的数据量超过时序库单次查询上限，请缩小时间范围");
            }
        }
        return exception;
    }

    private List<ChannelSample> queryChannelAggregated(PointEntity point, Instant from, Instant to, String step,
            List<String> codes, int limit) {
        String sqlInterval = AGGREGATION_STEPS.get(step);
        if (sqlInterval == null) throw new IllegalArgumentException("Unsupported telemetry aggregation step");
        String sql = "SELECT date_bin(INTERVAL '" + sqlInterval + "', time) AS time, "
                + "AVG(NULLIF(health_index, -1.0)) AS health_index, MAX(data_quality) AS data_quality, "
                + "MAX(CASE WHEN status_code BETWEEN 1 AND 3 OR (status_code = 0 AND health_index >= 0) "
                + "THEN status_code END) AS health_status, "
                + "MAX(CASE WHEN status_code = 5 OR (status_code = 4 AND data_quality = 0) "
                + "THEN 1 ELSE 0 END) AS stopped"
                + featureSelects(codes)
                + " FROM point_telemetry WHERE " + channelFilter(point)
                + " AND time >= TIMESTAMP '" + from + "' AND time <= TIMESTAMP '" + to
                + "' GROUP BY 1 ORDER BY time DESC LIMIT " + limit;
        List<ChannelSample> result = new ArrayList<>();
        try (Stream<PointValues> rows = client.queryPoints(sql)) {
            rows.forEach(row -> {
                int qualityCode = (int) number(row, "data_quality");
                Number healthStatus = row.getField("health_status") instanceof Number value ? value : null;
                boolean stopped = number(row, "stopped") > 0;
                int statusCode = aggregateStatusCode(healthStatus, qualityCode, stopped);
                result.add(new ChannelSample(toInstant(row.getTimestamp()), nullableNumber(row, "health_index", -1),
                        qualityCode, statusName(statusCode, healthStatus != null), readMetrics(row, codes)));
            });
        } catch (RuntimeException exception) {
            if (isMissingTable(exception)) return List.of();
            throw exception;
        }
        return result.reversed();
    }

    private List<ChannelSample> queryChannelOnce(PointEntity point, Instant from, Instant to, List<String> codes,
            int limit) {
        String sql = "SELECT time, NULLIF(health_index, -1.0) AS health_index, data_quality, status"
                + rawFeatureSelects(codes)
                + " FROM point_telemetry WHERE " + channelFilter(point)
                + " AND time >= TIMESTAMP '" + from + "' AND time <= TIMESTAMP '" + to
                + "' ORDER BY time DESC LIMIT " + limit;
        java.util.LinkedHashMap<Instant, ChannelSample> deduped = new java.util.LinkedHashMap<>();
        try (Stream<PointValues> rows = client.queryPoints(sql)) {
            rows.forEach(row -> {
                ChannelSample candidate = new ChannelSample(toInstant(row.getTimestamp()),
                        nullableNumber(row, "health_index", -1), (int) number(row, "data_quality"),
                        row.getField("status") instanceof String status ? status : null, readMetrics(row, codes));
                deduped.merge(candidate.timestamp(), candidate, InfluxTelemetryStore::preferScoredSample);
            });
        } catch (RuntimeException exception) {
            if (isMissingTable(exception)) return List.of();
            throw exception;
        }
        return new ArrayList<>(deduped.values()).reversed();
    }

    /** 同毫秒存在历史缓冲行（ANALYSIS_PENDING / ANALYSIS_BUFFERING）与正式评分行时，优先保留正式评分行。 */
    private static ChannelSample preferScoredSample(ChannelSample existing, ChannelSample incoming) {
        if (existing.healthIndex() != null) return existing;
        if (incoming.healthIndex() != null) return incoming;
        boolean existingPending = "ANALYSIS_PENDING".equals(existing.status()) || "ANALYSIS_BUFFERING".equals(existing.status());
        boolean incomingPending = "ANALYSIS_PENDING".equals(incoming.status()) || "ANALYSIS_BUFFERING".equals(incoming.status());
        return existingPending && !incomingPending ? incoming : existing;
    }

    private static String channelFilter(PointEntity point) {
        return "factory_id='" + sqlQuote(point.getDevice().getFactory().getFactoryCode())
                + "' AND device_code='" + sqlQuote(point.getDevice().getDeviceCode())
                + "' AND point_code='" + sqlQuote(point.getPointCode()) + "'";
    }

    /** 聚合特征片段：振动既有列保留哨兵过滤，其余特征原值聚合。包可见便于单测映射。 */
    static String featureSelects(List<String> codes) {
        StringBuilder select = new StringBuilder();
        for (String code : codes) {
            Double sentinel = VIBRATION_SENTINELS.get(code);
            select.append(", ").append(sentinel == null ? "AVG(" : "AVG(NULLIF(").append(sqlQuote(code));
            if (sentinel != null) select.append(", ").append(sentinel).append(")");
            select.append(") AS ").append(sqlQuote(code));
        }
        return select.toString();
    }

    /** 原始行特征片段：逐行取值不聚合（混入聚合函数而没有 GROUP BY 会被 InfluxDB 拒绝），振动既有列把哨兵值置空。 */
    static String rawFeatureSelects(List<String> codes) {
        StringBuilder select = new StringBuilder();
        for (String code : codes) {
            Double sentinel = VIBRATION_SENTINELS.get(code);
            select.append(", ").append(sentinel == null ? "" : "NULLIF(").append(sqlQuote(code));
            if (sentinel != null) select.append(", ").append(sentinel).append(")");
            select.append(" AS ").append(sqlQuote(code));
        }
        return select.toString();
    }

    private static java.util.Map<String, Double> readMetrics(PointValues row, List<String> codes) {
        java.util.Map<String, Double> metrics = new java.util.LinkedHashMap<>();
        for (String code : codes) {
            Double sentinel = VIBRATION_SENTINELS.get(code);
            Double value = sentinel == null ? plainNumber(row, code) : nullableNumber(row, code, sentinel);
            if (value != null) metrics.put(code, value);
        }
        return metrics;
    }

    private static Double plainNumber(PointValues row, String field) {
        return row.getField(field) instanceof Number number ? number.doubleValue() : null;
    }

    private static String sqlQuote(String value) { return value.replace("'", "''"); }

    @Override
    public void close() {
        accepting.set(false);
        writer.interrupt();
        try { writer.join(TimeUnit.SECONDS.toMillis(30)); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        if (writer.isAlive()) log.error("InfluxDB writer did not drain its queue during shutdown");
    }
}
