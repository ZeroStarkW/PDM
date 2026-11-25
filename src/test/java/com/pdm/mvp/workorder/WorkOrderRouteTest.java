package com.pdm.mvp.workorder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

/** 工单路由（B2）：设计 §6.1 的 v2 直推路由 /api/v2/workorders 与 v1 双写映射并存。 */
class WorkOrderRouteTest {
    @Test
    void v2WorkOrdersRouteIsMapped() {
        RequestMapping mapping = WorkOrderController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        List<String> paths = Arrays.asList(mapping.value());
        assertTrue(paths.contains("/api/v2/workorders"), "设计 §6.1 的第五条 v2 路由必须存在");
        assertTrue(paths.contains("/api/v1/work-orders") && paths.contains("/api/v1/workorders"),
                "既有 v1 路由保持不变");
    }
}
