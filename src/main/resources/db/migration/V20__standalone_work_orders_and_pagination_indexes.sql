-- 独立手工维修工单：解除工单对告警的强制绑定，补充设备/测点/来源/标题冗余定位列，
-- 并为服务端分页（告警、工单）建立排序与筛选索引。
-- 注意：application.yml 开启了 ddl-auto=validate，以下列定义必须与 JPA 实体完全一致。

ALTER TABLE pdm_work_order
    ALTER COLUMN alert_id DROP NOT NULL,
    ADD COLUMN device_id BIGINT REFERENCES pdm_device(id),
    ADD COLUMN point_id BIGINT REFERENCES pdm_measurement_point(id),
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'ALERT',
    ADD COLUMN title VARCHAR(128);

-- 历史告警工单回填设备/测点/标题（source 已由默认值置为 ALERT）
UPDATE pdm_work_order w
SET device_id = a.device_id,
    point_id = a.point_id,
    title = LEFT(a.dominant_factor, 128)
FROM pdm_alert_event a
WHERE w.alert_id = a.id
  AND w.device_id IS NULL;

ALTER TABLE pdm_work_order
    ALTER COLUMN device_id SET NOT NULL,
    ADD CONSTRAINT ck_work_order_source CHECK (source IN ('ALERT', 'MANUAL')),
    ADD CONSTRAINT ck_work_order_source_alert CHECK (
        (source = 'ALERT' AND alert_id IS NOT NULL) OR (source = 'MANUAL')
    );

-- 分页排序与多维筛选索引（created_at DESC, id DESC 与服务端排序一致）
CREATE INDEX idx_work_order_created_at ON pdm_work_order (created_at DESC, id DESC);
CREATE INDEX idx_work_order_device_status_created ON pdm_work_order (device_id, status, created_at DESC);
CREATE INDEX idx_alert_event_created_at ON pdm_alert_event (created_at DESC, id DESC);
CREATE INDEX idx_alert_event_status_level_created ON pdm_alert_event (status, alert_level, created_at DESC);
