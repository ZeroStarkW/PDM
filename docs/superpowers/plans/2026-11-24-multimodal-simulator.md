# 多模态设备模拟器实施计划

> **For agentic workers:** 本计划由当前 Codex 会话按任务顺序原生实施；遵守仓库现有代码风格和用户工作区状态。

**Goal:** 在现有 Python 仪表盘中模拟 MCSA 电机、断路器和调节阀，并把它们的数据经 MQTT 接入平台预测链路。

**Architecture:** Flask 仪表盘与现有旋转设备共享同一进程和 MQTT 客户端。模态负载按平台 2.1.0 契约发布；Spring 演示种子为 MCSA 补充幂等资产、测点与健康基线。

**Tech Stack:** Python 3、Flask、paho-mqtt、Java 21/Spring Boot、Vue 仪表盘现有 API、Mosquitto MQTT。

**Spec:** `docs/superpowers/specs/2026-11-24-multimodal-simulator-design.md`

## Global Constraints

- 单进程运行；不得新增服务或依赖。
- 旋转振动 v1/v2 遥测、波形快照和控制行为保持兼容。
- 所有新遥测使用 2.1.0 `phm/telemetry/{asset_id}/{channel_id}` 契约并由平台计算 HI/诊断。
- `PDM_MCSA_ALERTS_ENABLED` 默认值保持 false；已存在的资产、测点与基线行不得被覆盖。
- 设备分类与测点模态分开；演示泵保留 `GENERAL`，三台多模态设备分别使用自己的设备类型。
- 工作区已有大量未提交改动；不得回滚或重写与本功能无关的变更。
- 不新增测试；同步更新已有演示种子断言以匹配新增资产。按开发者指示不运行测试命令，完成静态审阅和非测试检查。

## Review Focus

- MCSA 必需特征和稳态/负载门禁：确认负载由 `operating_condition.load_pct` 桥接，指标有限且数值合理。
- MQTT 主题和资产/测点身份：设备编码与种子数据、主题和 JSON 字段完全一致。
- 断路器事件节律：仅动作时发布遥测和相同时间的快照，不发布错误的连续心跳。
- 各设备状态隔离：切换故障模式不得改变旋转设备或其他模态的状态；未知控制输入应被拒绝。
- 幂等演示种子：只补缺少的 MCSA 资产/测点/基线，保留现有数据和其它演示设备。
- 分类变更只更新三台多模态设备的 `deviceTypeId`，不改测点模态或其它资产字段。

---

### Task 1: 补齐 MCSA 演示资产和基线

**Files:**
- Modify: `src/main/java/com/pdm/mvp/asset/DemoDataInitializer.java`
- Modify: `src/test/java/com/pdm/mvp/asset/DemoDataInitializerTest.java` (已有断言同步调整，不新增测试)
- Modify: `README.md`

**Interfaces:**
- 设备编码 `MOTOR_M01`，测点编码 `CH_STATOR_CURRENT`，模态 `MCSA`。
- 健康基线：`sideband_ratio=0.001`、`carrier_amp_db=-33.0`，ACTIVE 状态。

- [x] 按现有存在即跳过模式补齐电机/测点和 MCSA ACTIVE 基线；已存在字段与基线行保持原样。
- [x] 更新根 README 的演示设备列表、首次启动和默认告警说明。
- [x] 静态检查新增种子特征码与 `McsaPlugin` 定义一致。

### Task 2: 增加多模态遥测合成和 MQTT 发布

**Files:**
- Create: `pdm_simulator_dashboard/pdm-simulator/multimodal_simulation.py`
- Modify: `pdm_simulator_dashboard/pdm-simulator/main.py`

**Interfaces:**
- 提供 MCSA/断路器/调节阀稳定、可预测的特征生成器及 2.1.0 payload builder。
- 电机和调节阀每秒发到 `phm/telemetry/MOTOR_M01/CH_STATOR_CURRENT` 与 `phm/telemetry/FEED_VALVE_FCV101/CH_VALVE_LOOP`。
- 断路器每约 10 秒及立即动作时发遥测，并同时发 `TRIP_ACTION_BURST` 快照。

- [x] 生成健康/故障两类电机（含转子导条）、断路器（慢分闸/线圈偏弱）与调节阀（卡涩/泄漏）特征。
- [x] 构造契约字段、身份、毫秒 UTC 时间戳、稳态工况与有限数值；保留各设备独立状态。
- [x] 在现有模拟循环内发布新模态；MQTT 不可用时继续维护本地仪表盘数据。

### Task 3: 添加仪表盘控制和多模态数据卡

**Files:**
- Modify: `pdm_simulator_dashboard/pdm-simulator/main.py`

**Interfaces:**
- `/api/data` 返回新增设备的最新模拟状态、指标和发布状态。
- `/api/control` 接受带设备标识的模式变更与断路器立即动作；旧旋转设备请求不变。

- [x] 每台设备显示类型、健康/故障模式、主要指标与 MQTT 状态。
- [x] 为每类设备添加独立状态控制和断路器动作按钮。
- [x] 校验允许的设备/模式/动作参数，避免坏请求破坏后台模拟循环。

### Task 4: 更新操作文档并做静态审阅

**Files:**
- Modify: `README.md`
- Modify: `pdm_simulator_dashboard/pdm-simulator/README.md`

- [x] 记录如何启用演示数据、启动模拟器、设置模式并观察对应预测工作台。
- [x] 说明 MCSA 的基线评分可观察，但告警阈值仍默认关闭。
- [x] 审阅改动差异，确认原旋转模拟及已有未提交改动未被覆盖；不运行测试。

### Task 5: 添加设备实体分类

**Files:**
- Modify: `src/main/java/com/pdm/mvp/asset/DeviceTypeService.java`
- Modify: `src/main/java/com/pdm/mvp/asset/DeviceEntity.java`
- Modify: `src/main/java/com/pdm/mvp/asset/DemoDataInitializer.java`
- Modify: `src/test/java/com/pdm/mvp/asset/DemoDataInitializerTest.java` (同步既有断言)
- Modify: `README.md`
- Modify: `pdm_simulator_dashboard/pdm-simulator/README.md`

**Interfaces:**
- `DeviceTypeService.getOrCreate(code, name)` 幂等返回设备类型实体。
- 分类编码：`ELECTRIC_MOTOR`、`CIRCUIT_BREAKER`、`CONTROL_VALVE`；泵仍为 `GENERAL`。

- [x] 幂等创建分类并将其分配给对应演示设备；保留其他设备字段与测点模态。
- [x] 同步已有种子断言和操作说明。
- [x] Java 编译并静态确认分类映射；不运行测试。
