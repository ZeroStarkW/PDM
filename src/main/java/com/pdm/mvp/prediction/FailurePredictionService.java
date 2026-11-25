package com.pdm.mvp.prediction;

import com.pdm.mvp.analytics.BaselineService;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.prediction.FailurePrediction.Confidence;
import com.pdm.mvp.prediction.FailurePrediction.Status;
import com.pdm.mvp.prediction.PredictionPointReader.PointSnapshot;
import com.pdm.mvp.telemetry.TelemetryPoint;
import com.pdm.mvp.timeseries.TelemetryStore;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class FailurePredictionService {
    private static final double THRESHOLD = 50.0;
    private static final Duration LOOKBACK = Duration.ofDays(30);
    private static final Duration MINIMUM_SPAN = Duration.ofHours(24);
    private static final int MINIMUM_OBSERVATIONS = 24;
    private static final Pattern QUERY_FILE_LIMIT_ERROR = Pattern.compile(
            "\\b(?:query would exceed file limit of \\d+ parquet files"
                    + "|query would scan \\d+ parquet files, exceeding the file limit)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Comparator<FailurePrediction> DEVICE_PRIORITY = Comparator
            .comparingInt((FailurePrediction result) -> switch (result.status()) {
                case THRESHOLD_REACHED -> 0;
                case ESTIMATE_AVAILABLE -> 1;
                case NO_DECLINE -> 2;
                case INSUFFICIENT_DATA -> 3;
            })
            .thenComparing(FailurePrediction::projectedDays, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(FailurePrediction::currentHi, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Comparator.comparingInt(FailurePrediction::observationCount).reversed());

    private final PredictionPointReader points;
    private final TelemetryStore telemetry;
    private final com.pdm.mvp.modality.TelemetryFreshnessPolicy freshness;
    private final ConcurrentMap<Long, CompletableFuture<FailurePrediction>> inFlight = new ConcurrentHashMap<>();

    public FailurePredictionService(PredictionPointReader points, TelemetryStore telemetry,
            com.pdm.mvp.modality.TelemetryFreshnessPolicy freshness) {
        this.points = points;
        this.telemetry = telemetry;
        this.freshness = freshness;
    }

    public FailurePrediction estimate(Long deviceId) {
        CompletableFuture<FailurePrediction> computation = new CompletableFuture<>();
        CompletableFuture<FailurePrediction> existing = inFlight.putIfAbsent(deviceId, computation);
        if (existing != null) {
            try {
                return existing.join();
            } catch (CompletionException failure) {
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                if (failure.getCause() instanceof Error cause) throw cause;
                throw failure;
            }
        }
        try {
            FailurePrediction result = calculate(deviceId);
            computation.complete(result);
            return result;
        } catch (RuntimeException | Error failure) {
            computation.completeExceptionally(failure);
            throw failure;
        } finally {
            inFlight.remove(deviceId, computation);
        }
    }

    private FailurePrediction calculate(Long deviceId) {
        List<PointSnapshot> devicePoints = points.load(deviceId);
        Instant now = Instant.now();
        return devicePoints.stream()
                .map(point -> estimatePoint(deviceId, point, now))
                .filter(result -> result.observationCount() > 0 || result.currentHi() != null)
                .min(DEVICE_PRIORITY)
                .orElseGet(() -> new FailurePrediction(deviceId, Status.INSUFFICIENT_DATA, null, null, null,
                        null, null, null, 0, null, null, null, Confidence.LOW));
    }

    private FailurePrediction estimatePoint(Long deviceId, PointSnapshot point, Instant now) {
        Instant lookbackStart = now.minus(LOOKBACK);
        // Scores generated before the current baseline may not be comparable.
        Instant baselineStart = point.baselineLockedAt();
        Instant queryStart = baselineStart != null && baselineStart.isAfter(lookbackStart) ? baselineStart : lookbackStart;
        List<TelemetryPoint> observations = hourlyHistory(point, queryStart, now).stream()
                .filter(sample -> !sample.timestamp().isBefore(lookbackStart) && !sample.timestamp().isAfter(now))
                .filter(sample -> validHi(sample.healthIndex()))
                .sorted(Comparator.comparing(TelemetryPoint::timestamp))
                .toList();
        Instant start = observations.isEmpty() ? null : observations.getFirst().timestamp();
        Instant end = observations.isEmpty() ? null : observations.getLast().timestamp();
        Double currentHi = currentScore(point, now);
        if (currentHi == null || observations.size() < MINIMUM_OBSERVATIONS
                || Duration.between(start, end).compareTo(MINIMUM_SPAN) < 0
                || !hasRecentHourlyHi(end, point.lastTelemetryAt())) {
            return new FailurePrediction(deviceId, Status.INSUFFICIENT_DATA, point.id(), point.pointCode(),
                    point.pointName(), currentHi, null, null, observations.size(), start, end, null, Confidence.LOW);
        }

        Trend trend = fit(observations, start);
        Status status;
        Double projectedDays = null;
        if (currentHi <= THRESHOLD) {
            status = Status.THRESHOLD_REACHED;
        } else if (trend.slopePerDay() >= 0) {
            status = Status.NO_DECLINE;
        } else {
            status = Status.ESTIMATE_AVAILABLE;
            projectedDays = (currentHi - THRESHOLD) / -trend.slopePerDay();
        }
        return new FailurePrediction(deviceId, status, point.id(), point.pointCode(), point.pointName(),
                currentHi, trend.slopePerDay(), projectedDays, observations.size(), start, end, trend.fitScore(),
                confidence(trend.fitScore(), observations.size(), Duration.between(start, end).toHours() + 1));
    }

    private List<TelemetryPoint> hourlyHistory(PointSnapshot point, Instant from, Instant to) {
        if (point.lastTelemetryAt() == null || !from.isBefore(to)) return List.of();

        PointEntity lookup = point.telemetryLookup();
        List<TelemetryPoint> samples = new ArrayList<>();
        // 优先单次查询拉取整段 1h 聚合；仅当触发 InfluxDB Parquet 文件扫描上限时按小时二分降级
        appendHourlyChunk(lookup, from, to, samples);
        return samples;
    }

    private void appendHourlyChunk(PointEntity lookup, Instant from, Instant to, List<TelemetryPoint> samples) {
        try {
            samples.addAll(telemetry.query(lookup, from, to, "1h"));
        } catch (RuntimeException failure) {
            Instant firstHour = from.truncatedTo(ChronoUnit.HOURS);
            long hourSpan = ChronoUnit.HOURS.between(firstHour, to.truncatedTo(ChronoUnit.HOURS));
            if (hourSpan == 0 || !isQueryFileLimitError(failure)) throw failure;

            // Split between hours so each hourly average is still calculated in a single query.
            Instant split = firstHour.plus((hourSpan + 1) / 2, ChronoUnit.HOURS);
            appendHourlyChunk(lookup, from, split.minusNanos(1), samples);
            appendHourlyChunk(lookup, split, to, samples);
        }
    }

    private static boolean isQueryFileLimitError(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && QUERY_FILE_LIMIT_ERROR.matcher(message).find()) return true;
        }
        return false;
    }

    private static boolean hasRecentHourlyHi(Instant latestBucket, Instant latestScore) {
        // The current hour's first scored sample may still be queued for writing to InfluxDB.
        Instant previousHour = latestScore.truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        return !latestBucket.isBefore(previousHour);
    }

    private Double currentScore(PointSnapshot point, Instant now) {
        // 新鲜度按测点模态解析：事件型模态动作间隔可达数天，沿用全局 5 分钟时限会永远判 STALE
        Instant cutoff = now.minus(freshness.staleAfter(point.modalityType()));
        Instant sampledAt = point.lastTelemetryAt();
        if (point.baselineStatus() != BaselineService.ACTIVE || !validHi(point.lastHealthIndex())
                || point.ingestedAt() == null || point.ingestedAt().isBefore(cutoff)
                || sampledAt == null || sampledAt.isBefore(cutoff) || sampledAt.isAfter(now)
                || point.qualityCode() != null && point.qualityCode() != 0) {
            return null;
        }
        return point.lastHealthIndex();
    }

    private static boolean validHi(Double value) {
        return value != null && Double.isFinite(value) && value >= 0 && value <= 100;
    }

    private static Trend fit(List<TelemetryPoint> observations, Instant start) {
        int count = observations.size();
        long hourSum = observations.stream().mapToLong(sample -> ChronoUnit.HOURS.between(start, sample.timestamp()))
                .sum();
        double firstHi = observations.getFirst().healthIndex();
        double meanChange = observations.stream().mapToDouble(sample -> sample.healthIndex() - firstHi)
                .average().orElseThrow();
        double timeSumSquares = 0;
        double hiSumSquares = 0;
        double crossSum = 0;
        double crossMagnitude = 0;
        for (TelemetryPoint sample : observations) {
            // Multiplying by count keeps the centered hour offset exact even for fractional means.
            double time = count * ChronoUnit.HOURS.between(start, sample.timestamp()) - hourSum;
            double change = sample.healthIndex() - firstHi;
            double hi = change - meanChange;
            timeSumSquares += time * time;
            hiSumSquares += hi * hi;
            // Centered times sum to zero, so subtracting meanChange does not change covariance.
            double crossProduct = time * change;
            crossSum += crossProduct;
            crossMagnitude += Math.abs(crossProduct);
        }
        // Treat cancellation within accumulated floating-point roundoff as zero covariance.
        if (Math.abs(crossSum) <= count * Math.ulp(crossMagnitude)) crossSum = 0;
        double slope = 24.0 * count * crossSum / timeSumSquares;
        Double fitScore = hiSumSquares == 0 ? null
                : Math.min(1.0, (crossSum * crossSum) / (timeSumSquares * hiSumSquares));
        return new Trend(slope, fitScore);
    }

    private static Confidence confidence(Double fitScore, int observations, long possibleObservations) {
        double coverage = (double) observations / possibleObservations;
        if (fitScore != null && observations >= 72 && coverage >= 0.75 && fitScore >= 0.8) return Confidence.HIGH;
        if (fitScore != null && observations >= 48 && coverage >= 0.50 && fitScore >= 0.6) return Confidence.MEDIUM;
        return Confidence.LOW;
    }

    private record Trend(double slopePerDay, Double fitScore) {}
}
