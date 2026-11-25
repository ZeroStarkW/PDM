-- 多模态通道快照幂等（协议 2.1.0 §2.2）：同测点同采样时刻只保留最新入库的一行，
-- MQTT QoS1 重投/并发直推不再产生重复快照（读侧服务按 (point_id, sampled_at) 幂等重放）。
-- 先按 id 保留最大者去重（幂等：无重复时 0 行受影响，可重复执行），再建唯一约束。
DELETE FROM pdm_channel_snapshot a
    USING pdm_channel_snapshot b
    WHERE a.point_id = b.point_id
      AND a.sampled_at = b.sampled_at
      AND a.id < b.id;
ALTER TABLE pdm_channel_snapshot
    ADD CONSTRAINT uk_channel_snapshot_point_time UNIQUE (point_id, sampled_at);
