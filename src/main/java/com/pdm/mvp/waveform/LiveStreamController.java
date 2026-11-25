package com.pdm.mvp.waveform;

import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.asset.PointRepository;
import com.pdm.mvp.bearing.BearingGeometryCalculator;
import com.pdm.mvp.bearing.BearingLibraryEntity;
import com.pdm.mvp.dsp.BandRms;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 实时频谱示波器推送（文档 §7.2）：SSE 端点 ws 路径语义 /ws/v1/live-stream?pointId=，
 * 以 2Hz 帧率推送 SPECTRUM_FRAME（时域波形 + FFT 谱 + 包络谱 + 1X~6X 谐波光标 +
 * BPFO/BPFI/BSF 故障线幅值与 SNR），故障线随测点绑定轴承与转速实时解算。
 */
@RestController
public class LiveStreamController {
    private static final long FRAME_INTERVAL_MS = 500;
    private static final long HEARTBEAT_FRAMES = 60;
    private static final String[] HARMONIC_FAULT_LINES = {"BPFO", "BPFI", "BSF"};

    private final WaveformService waveforms;
    private final WaveframeService frames;
    private final PointRepository points;
    private final JsonMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final Map<SseEmitter, Subscription> subscribers = new ConcurrentHashMap<>();
    private final AtomicLong frameId = new AtomicLong();

    public LiveStreamController(WaveformService waveforms, WaveframeService frames, PointRepository points,
            JsonMapper mapper) {
        this.waveforms = waveforms;
        this.frames = frames;
        this.points = points;
        this.mapper = mapper;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "pdm-live-stream");
            thread.setDaemon(true);
            return thread;
        });
    }

    @GetMapping(path = "/ws/v1/live-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("pointId") Long pointId) {
        // buildFrame 在调度线程读取 device/bearing（均 LAZY，且 open-in-view 已关闭）：订阅时就连同关联一并取回，
        // 否则推流线程脱离会话触发 LazyInitializationException，SSE 连接会在首帧崩掉
        PointEntity point = points.findByIdWithDeviceAndBearing(pointId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "测点不存在"));
        WaveframeService.Waveframe frame = frames.frame(pointId);
        if (frame == null) {
            // 服务重启后兜底：查询最近快照并重建缓存（无快照时抛 404）
            waveforms.latest(pointId);
            frame = frames.frame(pointId);
        }
        if (frame == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该测点还没有波形快照，无法开启实时流");

        SseEmitter emitter = new SseEmitter(0L);
        Subscription subscription = new Subscription();
        subscribers.put(emitter, subscription);
        Runnable cleanup = () -> cancel(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());

        AtomicLong tick = new AtomicLong();
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            if (subscription.isClosed()) return;
            try {
                long count = tick.incrementAndGet();
                if (count % HEARTBEAT_FRAMES == 0) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    return;
                }
                WaveframeService.Waveframe current = frames.frame(pointId);
                if (current == null) return;
                String payload = mapper.writeValueAsString(buildFrame(point, current));
                emitter.send(SseEmitter.event().name("spectrum_frame").data(payload, MediaType.APPLICATION_JSON));
            } catch (Exception exception) {
                cancel(emitter);
                emitter.completeWithError(exception);
            }
        }, 0, FRAME_INTERVAL_MS, TimeUnit.MILLISECONDS);
        subscription.attach(task);
        return emitter;
    }

    /** 组装一帧：静态波形/谱 + 动态谐波光标与轴承故障线（优先取实时变频转速，回退设备额定转速）。 */
    Map<String, Object> buildFrame(PointEntity point, WaveframeService.Waveframe frame) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "SPECTRUM_FRAME");
        payload.put("frame_id", frameId.incrementAndGet());
        payload.put("timestamp", frame.timestamp());
        payload.put("sampling_rate", frame.samplingRate());
        payload.put("unit", frame.unit());
        payload.put("point_id", point.getId());
        payload.put("point_code", point.getPointCode());
        double rpm = resolveRpm(point);
        payload.put("rpm", rpm);
        payload.put("time_waveform", frame.waveform());
        payload.put("fft_freqs", frame.fftFreqs());
        payload.put("fft_amps", frame.fftAmps());
        payload.put("envelope_freqs", frame.envelopeFreqs());
        payload.put("envelope_amps", frame.envelopeAmps());

        double rotationHz = rpm / 60.0;
        List<Map<String, Object>> harmonics = new ArrayList<>();
        if (rotationHz > 0) {
            for (int order = 1; order <= 6; order++) {
                harmonics.add(Map.of("order", order, "hz", round(rotationHz * order)));
            }
        }
        payload.put("harmonics", harmonics);

        BearingLibraryEntity bearing = point.getBearingModel();
        List<Map<String, Object>> faultLines = new ArrayList<>();
        if (bearing != null && rotationHz > 0 && frame.envelopeFreqs().length > 0) {
            var factors = BearingGeometryCalculator.factors(bearing);
            var frequencies = BearingGeometryCalculator.frequencies(factors, rotationHz);
            double floor = noiseFloor(frame.envelopeFreqs(), frame.envelopeAmps(), 30.0, 500.0);
            faultLines.add(faultLine("BPFO", frequencies.bpfoHz(), frame, floor));
            faultLines.add(faultLine("BPFI", frequencies.bpfiHz(), frame, floor));
            faultLines.add(faultLine("BSF", frequencies.bsfHz(), frame, floor));
            faultLines.add(faultLine("FTF", frequencies.ftfHz(), frame, floor));
        }
        payload.put("fault_lines", faultLines);
        payload.put("bearing_model", bearing == null ? null : bearing.getModelName());
        return payload;
    }

    private double resolveRpm(PointEntity point) {
        Double liveRpm = frames.latestRpm(point.getId());
        if (liveRpm != null && Double.isFinite(liveRpm) && liveRpm > 0) return liveRpm;
        var device = point.getDevice();
        if (device != null && device.getRpmNominal() != null && device.getRpmNominal().doubleValue() > 0) {
            return device.getRpmNominal().doubleValue();
        }
        return 0.0;
    }

    private Map<String, Object> faultLine(String name, double hz, WaveframeService.Waveframe frame, double floor) {
        double amplitude = BandRms.of(frame.envelopeFreqs(), frame.envelopeAmps(), hz);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("name", name);
        line.put("hz", round(hz));
        line.put("amp", round(amplitude));
        line.put("snr", round(amplitude / Math.max(1e-9, floor)));
        return line;
    }

    /** 包络谱噪底（低频区幅值中位数），供故障线 SNR 计算。 */
    static double noiseFloor(double[] freqs, double[] amps, double lowHz, double highHz) {
        List<Double> values = new ArrayList<>();
        for (int i = 0; i < freqs.length; i++) {
            if (freqs[i] < lowHz || freqs[i] > highHz) continue;
            values.add(amps[i]);
        }
        if (values.isEmpty()) return 0.0;
        values.sort(Comparator.naturalOrder());
        return values.get(values.size() / 2);
    }

    private static double round(double value) { return Math.round(value * 1000.0) / 1000.0; }

    private void cancel(SseEmitter emitter) {
        Subscription subscription = subscribers.remove(emitter);
        if (subscription != null) subscription.close();
    }

    int activeSubscriberCount() {
        return subscribers.size();
    }

    @PreDestroy
    void shutdown() {
        for (SseEmitter emitter : List.copyOf(subscribers.keySet())) {
            cancel(emitter);
            emitter.complete();
        }
        subscribers.clear();
        scheduler.shutdownNow();
    }

    /** 解决 initialDelay=0 首帧即时异常与 Map.put 之间的并发竞态，杜绝定时任务泄漏。 */
    private static final class Subscription {
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> future;

        void attach(ScheduledFuture<?> task) {
            this.future = task;
            if (closed.get()) task.cancel(false);
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            ScheduledFuture<?> task = this.future;
            if (task != null) task.cancel(false);
        }

        boolean isClosed() {
            return closed.get();
        }
    }
}
