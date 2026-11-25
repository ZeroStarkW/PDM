package com.pdm.mvp.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pdm.mvp.asset.AssetLookup;
import com.pdm.mvp.asset.DeviceEntity;
import com.pdm.mvp.asset.FactoryEntity;
import com.pdm.mvp.asset.PointEntity;
import com.pdm.mvp.modality.ModalityTypes;
import com.pdm.mvp.snapshot.ChannelSnapshotViews.ValveLoop;
import com.pdm.mvp.timeseries.TelemetryStore;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 回环迟滞端点：window 解析/截断、模态门槛（200 UNSUPPORTED_MODALITY）、NO_DATA 空态与 OK 组装；
 * 只读事务边界（懒加载工厂链安全，B1）；from/to 为样本实际覆盖范围并携带 truncated/limit（B7）。
 */
class ValveLoopEndpointTest {
    private final AssetLookup assets = mock(AssetLookup.class);
    private final TelemetryStore store = mock(TelemetryStore.class);
    private final ValveLoopController controller = new ValveLoopController(assets, store);

    private PointEntity point(String modalityType) {
        PointEntity point = mock(PointEntity.class);
        when(point.getId()).thenReturn(7L);
        when(point.getPointCode()).thenReturn("CH_VALVE_LOOP");
        when(point.getModalityType()).thenReturn(modalityType);
        return point;
    }

    private TelemetryStore.ChannelSample sample(long epochMs, Double cv, Double fb) {
        Map<String, Double> metrics = new java.util.LinkedHashMap<>();
        if (cv != null) metrics.put("cv_pct", cv);
        if (fb != null) metrics.put("fb_pct", fb);
        return new TelemetryStore.ChannelSample(Instant.ofEpochMilli(epochMs), null, 0, "HEALTHY", metrics);
    }

    @Test
    void hysteresisIsReadOnlyTransactionalForLazyFactoryChain() {
        // B1：时序层解引用 point.getDevice().getFactory()，open-in-view 关闭时必须在只读事务内执行
        Method method = null;
        for (Method candidate : ValveLoopController.class.getDeclaredMethods()) {
            if ("hysteresis".equals(candidate.getName())) method = candidate;
        }
        assertNotNull(method);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional, "hysteresis 必须声明 @Transactional");
        assertTrue(transactional.readOnly(), "查询路径应为 readOnly 事务");
    }

    @Test
    void realPointEntityGraphIsAnalyzedWithoutLazyInitialization() {
        // 真实实体图（plain 构造器，非 mock）：时序层可安全解引用 设备→工厂 懒加载链
        FactoryEntity factory = new FactoryEntity("FACT_01", "示范生产厂");
        DeviceEntity device = new DeviceEntity(factory, "FEED_VALVE_FCV101", "feed 调节阀", null, null, null);
        PointEntity realPoint = new PointEntity(device, "CH_VALVE_LOOP", "阀位反馈回路", null);
        realPoint.setModalityType(ModalityTypes.CONTROL_VALVE_PROCESS);
        when(assets.resolvePoint("CH_VALVE_LOOP")).thenReturn(realPoint);
        when(store.queryChannel(eq(realPoint), any(), any(), eq("raw"), any(), anyInt())).thenReturn(List.of(
                sample(1000, 0.0, 0.0), sample(2000, 25.0, 23.0), sample(3000, 50.0, 48.0)));

        ValveLoop loop = controller.hysteresis("CH_VALVE_LOOP", "PT1H");

        assertEquals(ValveLoopController.STATUS_OK, loop.status());
        assertEquals(3, loop.sampleCount());
        assertEquals(2.0, loop.analysis().maxDeviationPct(), 1e-9);
        assertEquals("CH_VALVE_LOOP", loop.channelCode());
    }

    @Test
    void windowParsingRejectsInvalidAndNonPositiveDurations() {
        assertThrows(ResponseStatusException.class, () -> controller.parseWindow("yesterday"));
        assertThrows(ResponseStatusException.class, () -> controller.parseWindow("PT0S"));
        assertThrows(ResponseStatusException.class, () -> controller.parseWindow("PT-30M"));
        assertThrows(ResponseStatusException.class, () -> controller.parseWindow(null));
    }

    @Test
    void oversizedWindowIsClampedNotRejected() {
        assertEquals(Duration.ofDays(7), controller.parseWindow("P30D"));
        assertEquals(Duration.ofHours(1), controller.parseWindow("PT1H"));
    }

    @Test
    void nonValveModalityReturnsUnsupportedModalityWith200() {
        PointEntity point = point(ModalityTypes.ROTATING_VIB);
        when(assets.resolvePoint("PUMP_DE_VIB")).thenReturn(point);
        ValveLoop loop = controller.hysteresis("PUMP_DE_VIB", "PT1H");
        assertEquals(ValveLoopController.STATUS_UNSUPPORTED, loop.status());
        assertEquals(0, loop.sampleCount());
        assertEquals(0, loop.points().size());
        assertEquals(0.0, loop.analysis().maxDeviationPct(), 1e-9);
        assertFalse(loop.truncated());
        // 控制器走带行数上限的 6 参数重载（mock 不会委托到 5 参数 default），桩与验证均须对准该重载
        verify(store, org.mockito.Mockito.never())
                .queryChannel(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void noPairedSamplesYieldsNoDataWithNullWindowBounds() {
        PointEntity point = point(ModalityTypes.CONTROL_VALVE_PROCESS);
        when(assets.resolvePoint("CH_VALVE_LOOP")).thenReturn(point);
        when(store.queryChannel(any(), any(), any(), eq("raw"), any(), anyInt())).thenReturn(List.of(
                sample(1000, 12.0, null), sample(2000, null, 11.0)));
        ValveLoop loop = controller.hysteresis("CH_VALVE_LOOP", "PT1H");
        assertEquals(ValveLoopController.STATUS_NO_DATA, loop.status());
        assertEquals(0, loop.sampleCount());
        assertNull(loop.from());
        assertNull(loop.to());
        assertFalse(loop.truncated());
    }

    @Test
    void assemblesLoopFromPairedRawSamplesWithEffectiveRange() {
        PointEntity point = point(ModalityTypes.CONTROL_VALVE_PROCESS);
        when(assets.resolvePoint("CH_VALVE_LOOP")).thenReturn(point);
        when(store.queryChannel(any(), any(), any(), eq("raw"), any(), anyInt())).thenReturn(List.of(
                sample(1000, 0.0, 0.0), sample(2000, 25.0, 23.0), sample(3000, 50.0, 48.0),
                sample(4000, 25.0, 25.0), sample(5000, 0.0, 0.0),
                sample(6000, 30.0, null)));
        ValveLoop loop = controller.hysteresis("CH_VALVE_LOOP", "PT1H");
        assertEquals(ValveLoopController.STATUS_OK, loop.status());
        assertEquals(5, loop.sampleCount(), "仅保留同时携带 cv/fb 的样本");
        assertEquals(5, loop.points().size());
        // from/to 报告样本实际覆盖范围（首/末配对样本），不再是请求窗口边界
        assertEquals(1000L, loop.from());
        assertEquals(5000L, loop.to());
        assertEquals(ValveLoopController.RAW_ROW_LIMIT, loop.limit());
        assertEquals(1, loop.analysis().reversalCount(), "0→50→0 单次换向");
        assertEquals(2.0, loop.analysis().maxDeviationPct(), 1e-9);
        assertEquals(ModalityTypes.CONTROL_VALVE_PROCESS, loop.modalityType());
        assertFalse(loop.truncated());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> codes = ArgumentCaptor.forClass(List.class);
        verify(store).queryChannel(any(), any(), any(), eq("raw"), codes.capture(), anyInt());
        assertEquals(List.of("cv_pct", "fb_pct"), codes.getValue());
        assertTrue(loop.points().get(1).cv() == 25.0 && loop.points().get(1).fb() == 23.0);
    }

    @Test
    void rawWindowHitsLimitReportsTruncation() {
        PointEntity point = point(ModalityTypes.CONTROL_VALVE_PROCESS);
        when(assets.resolvePoint("CH_VALVE_LOOP")).thenReturn(point);
        List<TelemetryStore.ChannelSample> saturated = new java.util.ArrayList<>();
        for (int i = 0; i < ValveLoopController.RAW_ROW_LIMIT; i++) saturated.add(sample(i, 10.0, 10.0));
        when(store.queryChannel(any(), any(), any(), eq("raw"), any(), anyInt())).thenReturn(saturated);

        ValveLoop loop = controller.hysteresis("CH_VALVE_LOOP", "P7D");

        assertTrue(loop.truncated(), "行数触及服务端上限必须如实报告截断");
        assertEquals(ValveLoopController.RAW_ROW_LIMIT, loop.limit());
        assertEquals(ValveLoopController.RAW_ROW_LIMIT, loop.sampleCount());
    }

    @Test
    void hysteresisQueryUsesDedicatedRowLimitInsteadOfSeriesLimit() {
        PointEntity point = point(ModalityTypes.CONTROL_VALVE_PROCESS);
        when(assets.resolvePoint("CH_VALVE_LOOP")).thenReturn(point);
        when(store.queryChannel(any(), any(), any(), eq("raw"), any(), anyInt())).thenReturn(List.of());

        controller.hysteresis("CH_VALVE_LOOP", "PT1H");

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(store).queryChannel(any(), any(), any(), eq("raw"), any(), limit.capture());
        assertEquals(20000, limit.getValue(), "回环查询使用放大的 raw 上限");
        assertTrue(ValveLoopController.RAW_ROW_LIMIT > 2000);
    }
}
