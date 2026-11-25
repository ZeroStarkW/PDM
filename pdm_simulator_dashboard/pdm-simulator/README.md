# PdM 多模态设备模拟器

这是一个与 PdM 平台通过 MQTT 集成的本地仿真仪表盘。它在一个 Python 进程中持续模拟旋转泵、MCSA 电机、10kV 断路器和工艺调节阀；设备模式互相独立，预测评分与诊断由平台计算。

## 启动

先启动平台及其 Mosquitto 服务，并确认根目录 `.env` 中 `PDM_DEMO_DATA=true`，使平台创建下列演示资产和测点。若 Broker 在模拟器启动后才启动，请重启模拟器。

```powershell
cd pdm_simulator_dashboard/pdm-simulator
uv run main.py
```

打开 <http://localhost:5000>。仪表盘默认连接 `127.0.0.1:1883`。

## 模拟设备

| 设备 | 系统设备分类 | 资产 / 测点 | 遥测主题 | 可选模拟状态 |
| --- | --- | --- | --- | --- |
| MCSA 异步电机 | `ELECTRIC_MOTOR`（电动机） | `MOTOR_M01 / CH_STATOR_CURRENT` | `phm/telemetry/MOTOR_M01/CH_STATOR_CURRENT` | 健康、转子导条故障 |
| 10kV 高压断路器 | `CIRCUIT_BREAKER`（高压断路器） | `BREAKER_10KV_02 / CH_TRIP_UNIT` | `phm/telemetry/BREAKER_10KV_02/CH_TRIP_UNIT` | 健康、分闸变慢、线圈电流偏弱 |
| Feed 调节阀 | `CONTROL_VALVE`（调节阀） | `FEED_VALVE_FCV101 / CH_VALVE_LOOP` | `phm/telemetry/FEED_VALVE_FCV101/CH_VALVE_LOOP` | 正常回环、卡涩/死区增大、阀内泄漏 |

电机和阀门每秒发布一帧 2.1.0 特征遥测。断路器约每 10 秒发布一次动作遥测与 `TRIP_ACTION_BURST` 曲线快照，也可在仪表盘手动触发。旋转设备继续发布旧 v1 与 2.0.0 数据，并保留原来的波形快照控制。

仪表盘卡片显示最近生成的特征，以及 MQTT 发布状态。切换模式只影响所选设备。未连接 MQTT 时，模拟器仍在本地生成数据并显示仪表盘；平台不会收到该数据。

## 平台预测行为

平台按测点模态进入已有插件和工作台。MCSA 用边频带/载波相对基线评分；为演示校准，MCSA 确定性告警仍默认关闭。54/40 dB 是待现场校准的候选阈值，不应视为生产诊断标准。

应用启动时，如果电动机、断路器或调节阀设备类型已经存在且尚无规则，规则页面会补齐候选规则：电机边频带间距 54/40 dB、断路器分闸时间倍率 1.10 与线圈电流倍率 0.80、调节阀死区倍率 2.5/5.0 与泄漏 RMS 0.2。此初始化不依赖 `PDM_DEMO_DATA`；已有规则（包括禁用规则）不会被覆盖。`>1.20` 断路器分闸倍率和 `>0.5` 阀门泄漏硬限由各自插件因素优先；上述默认值需结合现场数据校准。

仍可用仓库根目录的 `scripts/Publish-MultimodalSamples.ps1` 手动发布断路器和调节阀样本；与仪表盘同时运行时会产生重复的演示数据。
