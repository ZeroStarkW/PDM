package com.pdm.mvp.snapshot;

import com.pdm.mvp.snapshot.ChannelSnapshotViews.ChannelSnapshotView;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多模态通道快照接口（协议 2.1.0 §2.2 / 文档 §6.1）：
 * GET /api/v2/snapshots/{code}/latest —— {code} 为测点 ID 或全局唯一测点编码；
 * POST /api/v2/snapshots —— MQTT phm/snapshot 的 REST 直推孪生（边缘无 MQTT 时用）。
 */
@RestController
@RequestMapping("/api/v2/snapshots")
public class ChannelSnapshotController {
    private final ChannelSnapshotService snapshots;

    public ChannelSnapshotController(ChannelSnapshotService snapshots) { this.snapshots = snapshots; }

    @GetMapping("/{code}/latest")
    public ChannelSnapshotView latest(@PathVariable String code) {
        return snapshots.latest(code);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ChannelSnapshotView accept(@RequestBody PhmSnapshotPayload payload) {
        return snapshots.accept(payload);
    }
}
