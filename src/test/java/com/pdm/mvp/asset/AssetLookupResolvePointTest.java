package com.pdm.mvp.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** v2 通道定位健壮性（B12）：超长数字串按编码处理不 500；编码查找大小写不敏感。 */
class AssetLookupResolvePointTest {
    private final PointRepository points = mock(PointRepository.class);
    private final AssetLookup lookup = new AssetLookup(points);

    @Test
    void overlyLongNumericSegmentIsTreatedAsCodeInsteadOfThrowing() {
        // 23 位数字串：Long.parseLong 会溢出 500，现按测点编码查找（找不到 → 404）
        when(points.findByPointCodeOrderByIdAsc(anyString())).thenReturn(List.of());
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> lookup.resolvePoint("12345678901234567890123"));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(points).findByPointCodeOrderByIdAsc(code.capture());
        assertEquals("12345678901234567890123", code.getValue());
    }

    @Test
    void pointCodeLookupIsCaseInsensitive() {
        PointEntity point = mock(PointEntity.class);
        when(point.getId()).thenReturn(7L);
        when(points.findByPointCodeOrderByIdAsc("CH_TRIP_UNIT")).thenReturn(List.of(point));

        assertEquals(7L, lookup.resolvePoint("ch_trip_unit").getId(), "编码统一大写后查找");
    }

    @Test
    void shortNumericSegmentStillResolvesById() {
        PointEntity point = mock(PointEntity.class);
        when(point.getId()).thenReturn(7L);
        when(points.findById(7L)).thenReturn(Optional.of(point));

        assertNotNull(lookup.resolvePoint("7"));
    }
}
