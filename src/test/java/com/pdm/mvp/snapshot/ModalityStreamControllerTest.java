package com.pdm.mvp.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.snapshot.ChannelSnapshotViews.ChannelSnapshotView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** /ws/v2/stream 订阅决策：振动通道 409 引导回 v1、非振动订阅并补推初始快照帧、空态静默。 */
class ModalityStreamControllerTest {
    private final AssetLookup assets = mock(AssetLookup.class);
    private final ChannelSnapshotService snapshots = mock(ChannelSnapshotService.class);
    private final ModalityStreamHub hub = mock(ModalityStreamHub.class);
    private final ModalityStreamController controller = new ModalityStreamController(assets, snapshots, hub);

    private PointEntity point(String modalityType) {
        PointEntity point = mock(PointEntity.class);
        DeviceEntity device = mock(DeviceEntity.class);
        when(device.getDeviceCode()).thenReturn("BREAKER_10KV_02");
        when(point.getId()).thenReturn(7L);
        when(point.getDevice()).thenReturn(device);
        when(point.getPointCode()).thenReturn("CH_TRIP_UNIT");
        when(point.getModalityType()).thenReturn(modalityType);
        when(point.getSamplingRate()).thenReturn(10000);
        when(point.getEngineeringUnit()).thenReturn("A");
        return point;
    }

    private ChannelSnapshotView view() {
        return new ChannelSnapshotView(12L, 7L, "CH_TRIP_UNIT", "BREAKER_10KV_02",
                ModalityTypes.SWITCHGEAR_EVENT, "TRIP_ACTION_BURST", 1732262400000L,
                "2024-11-22T00:00:00Z", 10000.0, 9, "A", "LOCAL_VOLUME",
                "file:/ref", null, 96.5, List.of(0.0), Map.of(), Map.of(), Map.of(), Map.of());
    }

    @Test
    void vibrationChannelIsRejectedWith409ToGuideV1Fallback() {
        PointEntity point = point(ModalityTypes.ROTATING_VIB);
        when(assets.resolvePoint("PUMP_DE_VIB")).thenReturn(point);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.stream("PUMP_DE_VIB"));
        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("振动通道请使用 /ws/v1/live-stream", exception.getReason());
        verify(hub, never()).subscribe(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void eventChannelSubscribesAndPushesInitialSnapshotFrame() {
        PointEntity point = point(ModalityTypes.SWITCHGEAR_EVENT);
        when(assets.resolvePoint("CH_TRIP_UNIT")).thenReturn(point);
        ChannelSnapshotView snapshot = view();
        when(snapshots.latest("CH_TRIP_UNIT")).thenReturn(snapshot);

        SseEmitter emitter = controller.stream("CH_TRIP_UNIT");
        assertNotNull(emitter);
        ArgumentCaptor<SseEmitter> subscribed = ArgumentCaptor.forClass(SseEmitter.class);
        verify(hub).subscribe(org.mockito.ArgumentMatchers.eq(7L), subscribed.capture());
        assertEquals(emitter, subscribed.getValue());
        verify(hub).publishSnapshot(7L, snapshot);
    }

    @Test
    void emptySnapshotStateIsSilent() {
        PointEntity point = point(ModalityTypes.MCSA);
        when(assets.resolvePoint("CH_MCSA")).thenReturn(point);
        when(snapshots.latest("CH_MCSA")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "该测点还没有波形快照"));
        assertNotNull(controller.stream("CH_MCSA"));
        verify(hub).subscribe(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any());
        verify(hub, never()).publishSnapshot(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void subscriptionIsUnsubscribedWhenAnyStepAfterSubscribeFails() {
        // B5(c)：初始快照补推失败 → 立即摘除订阅，枢纽中不残留泄漏 emitter
        PointEntity point = point(ModalityTypes.SWITCHGEAR_EVENT);
        when(assets.resolvePoint("CH_TRIP_UNIT")).thenReturn(point);
        when(snapshots.latest("CH_TRIP_UNIT")).thenReturn(view());
        org.mockito.Mockito.doThrow(new RuntimeException("sse pipe broken"))
                .when(hub).publishSnapshot(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any());

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> controller.stream("CH_TRIP_UNIT"));

        ArgumentCaptor<SseEmitter> subscribed = ArgumentCaptor.forClass(SseEmitter.class);
        verify(hub).subscribe(org.mockito.ArgumentMatchers.eq(7L), subscribed.capture());
        verify(hub).unsubscribe(7L, subscribed.getValue());
    }
}
