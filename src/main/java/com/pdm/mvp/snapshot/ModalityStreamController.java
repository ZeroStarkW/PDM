package com.pdm.mvp.snapshot;

import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.snapshot.ChannelSnapshotViews.ChannelSnapshotView;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 多模态 v2 实时推流端点（文档 §6.2 GET /ws/v2/stream?channelCode={code}）：
 * {code} 经测点定位（ID 或全局唯一编码）；振动通道 409 引导回 /ws/v1/live-stream（避免双推）。
 * 订阅顺序：解析测点 → 预取初始快照（404 空态不报错）→ 订阅 → 立即注册清理回调 → 补推初始帧；
 * 订阅之后的任何失败都会摘除订阅，枢纽中绝不残留泄漏的 emitter。
 */
@RestController
public class ModalityStreamController {
    private static final long HEARTBEAT_INTERVAL_MS = 30_000;

    private final AssetLookup assets;
    private final ChannelSnapshotService snapshots;
    private final ModalityStreamHub hub;
    private final ScheduledExecutorService scheduler;
    private final Map<SseEmitter, Subscription> subscriptions = new ConcurrentHashMap<>();

    private record Subscription(Long pointId, ScheduledFuture<?> heartbeat) {}

    public ModalityStreamController(AssetLookup assets, ChannelSnapshotService snapshots, ModalityStreamHub hub) {
        this.assets = assets;
        this.snapshots = snapshots;
        this.hub = hub;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "pdm-modality-stream");
            thread.setDaemon(true);
            return thread;
        });
    }

    @GetMapping(path = "/ws/v2/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("channelCode") String code) {
        PointEntity point = assets.resolvePoint(code);
        if (ModalityTypes.ROTATING_VIB.equals(point.getModalityType()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "振动通道请使用 /ws/v1/live-stream");

        // 初始帧先取快照（可失败），再订阅：订阅成功后枢纽中的 emitter 一定能被清理
        ChannelSnapshotView initial = null;
        try {
            initial = snapshots.latest(code);
        } catch (ResponseStatusException ignored) {
            // 尚无快照属正常空态
        }
        SseEmitter emitter = new SseEmitter(0L);
        try {
            hub.subscribe(point.getId(), emitter);
            // 注册清理回调紧随订阅之后：完成/超时/错误都摘除订阅与心跳
            Runnable cleanup = () -> close(emitter);
            emitter.onCompletion(cleanup);
            emitter.onTimeout(cleanup);
            emitter.onError(ignored -> cleanup.run());
            // 订阅记录先于任何可失败步骤入表：补推/心跳调度抛错时 close() 也能摘除订阅
            subscriptions.put(emitter, new Subscription(point.getId(), null));
            if (initial != null) hub.publishSnapshot(point.getId(), initial);
            ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (Exception exception) {
                    close(emitter);
                    emitter.completeWithError(exception);
                }
            }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
            subscriptions.put(emitter, new Subscription(point.getId(), heartbeat));
            return emitter;
        } catch (RuntimeException failure) {
            // 订阅后的任何失败（补推/心跳调度等）都必须摘除订阅，防止 emitter 泄漏
            close(emitter);
            try {
                emitter.completeWithError(failure);
            } catch (RuntimeException ignored) {
                // 完成回调静默
            }
            throw failure;
        }
    }

    private void close(SseEmitter emitter) {
        Subscription subscription = subscriptions.remove(emitter);
        if (subscription == null) return;
        if (subscription.heartbeat() != null) subscription.heartbeat().cancel(false);
        hub.unsubscribe(subscription.pointId(), emitter);
    }

    @PreDestroy
    void shutdown() {
        subscriptions.keySet().forEach(SseEmitter::complete);
        subscriptions.clear();
        scheduler.shutdownNow();
    }
}
