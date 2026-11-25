# 旋转机械振动预测性维护系统（PdM-MVP）系统设计说明书

---

## 1. 系统概述与设计原则

### 1.1 建设背景与业务目标
针对现代流程制造与连续生产产线中关键旋转机械（离心泵、离心/轴流风机、三相异步电机、工业齿轮箱）非计划停机损失巨大、传统人工巡检受限于经验且时效性差、静态振动绝对阈值（如一刀切的 ISO 10816 标准）极易造成早期漏报与严重误报等核心痛点，本系统旨在搭建一套低试错成本、高诊断精度的预测性维护最小可行产品（MVP）。

系统通过“边缘特征提纯 + 云端相对基线建模 + 非线性健康度劣化评估”技术路径，达成以下核心业务与工程目标：
1. **微观故障早期截获**：相较于传统时域总振动告警，将轴承疲劳剥落、转子微裂纹等典型机械隐患的预警窗口提前 **2 ~ 4 周**。
2. **故障性质精准定性**：通过 1X、2X 阶次特征及轴承特征频率，直接输出明确的大方向排查清单（转子不平衡、对中超差、轴承磨损），避免盲目拆机。
3. **极低带宽与存储开销**：边缘端就地完成原始时域波形的加窗截断与 2048 点 FFT 频域变换，向云端只回传结构化指标，网络上行带宽消耗降低 **99% 以上**。
4. **业务运维闭环**：通过告警多级防抖滤波，与企业微信/钉钉 Webhook 无缝联动，自动推送结构化图文卡片与排查建议。

### 1.2 核心架构原则
* **边端协同（Edge-Cloud Synergy）**：高频振动物理量就地计算，特征与健康状态云端统一汇聚评估，规避原始波形上云的带宽阻塞。
* **动静解耦存储（Storage Segregation）**：关系型事务数据（资产、测点、基线元数据、工单）与时序指标（秒级/分钟级健康分、特征幅值）采用专门引擎分别存储，保障系统弹性伸缩。
* **个体相对基线优先（Relative Degradation First）**：以设备自身稳定工况的“健康初态”为坐标原点，核心监控相对自身基线的倍率与偏离度，打破“千机一面”的静态死门限。
* **渐进式演进与高可用（Evolutionary & Production-Ready）**：去除 MVP 阶段不切实际的深度学习训练黑盒，聚焦高置信度机理规则模型，接口规范完全兼容后续 2.0 阶段的大模型扩充。

---

## 2. 总体物理与逻辑架构设计

### 2.1 物理部署与网络拓扑

```
[现场物理机房 / 产线工位]
  ┌────────────────────────────────────────────────────────┐
  │ 工业无线振温传感器 (三轴加速度 + 速度 RMS + 接触式表面温度)  │
  └──────────────────────────┬─────────────────────────────┘
                             │ BLE 5.0 Mesh / 433MHz / RS-485
                             ▼
  ┌────────────────────────────────────────────────────────┐
  │ 边缘计算网关 (ARM Cortex-A55 / Linux / 本地 SQLite 缓存)  │
  │ • 任务: 2048点加窗FFT、寻峰、时域统计、断网保护断点续传      │
  └──────────────────────────┬─────────────────────────────┘
                             │ 工业内网 / 4G VPN (MQTT over TLS / Port 8883)
═════════════════════════════╪═════════════════════════════════════════════════
[工厂内网 DMZ / 工业私有云]    ▼
  ┌────────────────────────────────────────────────────────┐
  │ 工业消息集群 (EMQX Enterprise / Broker)                │
  └──────────────────────────┬─────────────────────────────┘
                             │ TCP / Spring Integration
                             ▼
  ┌────────────────────────────────────────────────────────┐
  │ 核心业务与算法中台 (Java / Spring Boot 3.x 集群)        │
  │ • Ingestion Engine: 数据质检、探头脱落拦截、死区过滤     │
  │ • Analytics Engine: 动态容差带寻峰、相对基线状态机、HI打分│
  │ • Alert Engine: 滑动窗口防抖、多级工单触发、Webhook 推送  │
  └─────────────┬──────────────────────────┬───────────────┘
                │ JDBC / REST              │ Native Connector
                ▼                          ▼
  ┌───────────────────────┐  ┌─────────────────────────────┐
  │ 关系数据库 (MySQL 8.0) │  │ 时序数据库 (TDengine 3.x)   │
  │ • 设备台账、测点参数    │  │ • 秒级/分钟级振动特征时序   │
  │ • 固化健康基线、工单    │  │ • 健康度得分 (HI) 走势      │
  └───────────────────────┘  └─────────────────────────────┘
                │ WebSocket / JSON-RPC
                ▼
  ┌────────────────────────────────────────────────────────┐
  │ 客户端展示层 (Vue 3 + Pinia + ECharts / 企业微信 & 钉钉) │
  └────────────────────────────────────────────────────────┘
```

### 2.2 逻辑分层架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 1. 展现层 (Presentation Layer)                                          │
│    • 设备健康看板与风险排序         • 测点频谱与时域特征 ECharts 交互仪   │
│    • 设备健康趋势及测点证据下钻     • 钉钉 / 微信移动端设备告警图文卡片   │
├─────────────────────────────────────────────────────────────────────────┤
│ 2. 业务逻辑层 (Application & Business Layer)                           │
│    • 设备资产与测点拓扑管理         • 故障工单流转与闭环处理              │
│    • 基线自学习重置与人工审计状态机 • 告警策略与通知路由引擎              │
├─────────────────────────────────────────────────────────────────────────┤
│ 3. 算法计算引擎层 (Analytics Engine)                                     │
│    • 动态转差率/阶次锁定 (1X/2X)    • 局部信噪比非极大值抑制寻峰 (NMS)    │
│    • 稳态 P50 基线统计估算          • 修正 Sigmoid 非线性健康得分模型 (HI)│
├─────────────────────────────────────────────────────────────────────────┤
│ 4. 数据接入与治理层 (Data Ingestion Layer)                             │
│    • MQTT 异步消费与报文反序列化   • 探头脱落与断线拦截过滤               │
│    • 异常飞刺中值滤波               • 批量异步入库 (Batch Ingestion)      │
├─────────────────────────────────────────────────────────────────────────┤
│ 5. 持久化存储层 (Persistence Layer)                                     │
│    • 关系库: 拓扑结构、配置元数据   • 时序库: 高吞吐标量时序与降采样聚合  │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 边缘计算与网络接口协议

### 3.1 边缘计算职责与参数定义
* **采样频率 ($f_s$)**：$2048 \text{ Hz}$（针对低频机泵转速）或 $25600 \text{ Hz}$（高频轴承通道）。
* **采样点数 ($N$)**：$2048$ 点（对应连续物理时间约为 $1.0 \text{ s}$）。
* **频率分辨率 ($\Delta f$)**：$\Delta f = \frac{f_s}{N} = \frac{2048}{2048} = 1.0 \text{ Hz/bin}$。
* **窗函数**：汉宁窗（Hanning Window），有效压制非周期截断造成的能量泄漏。
* **本地防掉线机制**：网关运行轻量级嵌入式 SQLite 循环队列。当上行网络中断时，数据本地持久化入队；网络握手恢复后，按照时间戳升序回放补传。

### 3.2 MQTT 上行通信报文规范（JSON Payload）

* **Topic 规范**：`pdm/v1/{factory_id}/{device_code}/{point_code}/telemetry`
* **QoS 级别**：QoS 1（至少送达一次）
* **报文样例**：

```json
{
  "timestamp": 1773891000000,
  "factory_id": "FACT_01",
  "device_code": "PUMP_P102A",
  "point_code": "DE_RADIAL",
  "data_quality": 0,
  "telemetry": {
    "temperature": 48.5,
    "v_rms": 1.25,
    "a_peak": 8.42,
    "a_kurtosis": 3.12,
    "freq_1x_amp": 1.18,
    "freq_2x_amp": 0.45,
    "envelope_rms": 0.12
  },
  "top_peaks": [
    { "frequency": 24.8, "amplitude": 1.18, "snr": 15.2 },
    { "frequency": 49.6, "amplitude": 0.45, "snr": 6.8 },
    { "frequency": 142.5, "amplitude": 0.38, "snr": 4.1 }
  ]
}
```

* **字段契约与约束规范**：

| 字段路径 | 数据类型 | 单位 | 校验规则 / 约束 |
| :--- | :--- | :--- | :--- |
| `timestamp` | Long | ms | Unix 毫秒时间戳，平台层校验误差容忍范围在 $\pm 5\text{ 分钟}$ 内 |
| `data_quality` | Integer | - | `0: 正常`, `1: 电压不足`, `2: 超量程削波`, `3: 探头异常` |
| `telemetry.v_rms` | Double | mm/s | 振动速度有效值（$10\text{Hz}\sim 1000\text{Hz}$），若 $< 0.05$ 判定停机或脱落 |
| `telemetry.a_peak` | Double | $\text{m/s}^2$ | 加速度真峰值 |
| `telemetry.a_kurtosis`| Double | - | 峭度无量纲参数，纯正弦波为 1.5，高斯白噪为 3.0，高频冲击 $> 5.0$ |
| `top_peaks` | Array | - | 边缘提取的前 3~5 个最高信噪比特征峰，供中心端故障诊断比对 |

---

## 4. 核心算法与计算引擎详细设计

### 4.1 核心数据处理时序流（Sequence Diagram）

```
[边缘网关]        [EMQX]        [Ingestion]      [Analytics]      [TDengine]      [AlertService]
    │               │                │                │               │                 │
    │──Publish─────>│                │                │               │                 │
    │  (Telemetry)  │──Route────────>│                │               │                 │
    │               │                │──1. 质检与脱落 ─>│               │                 │
    │               │                │   拦截校验     │               │                 │
    │               │                │                │──2. 判定工况──>│                 │
    │               │                │                │   (停机/稳态) │                 │
    │               │                │                │               │                 │
    │               │                │                │──3. 基线状态机 │                 │
    │               │                │                │   更新/提取   │                 │
    │               │                │                │               │                 │
    │               │                │                │──4. 计算 HI 得分 (非线性模型)     │
    │               │                │                │               │                 │
    │               │                │                │──5. 异步落库──>│                 │
    │               │                │                │     (Batch)   │                 │
    │               │                │                │                                 │
    │               │                │                │──6. 判定越限 (HI < 50) ─────────>│
    │               │                │                │                                 │──滑动窗口防抖
    │               │                │                │                                 │──触发通知/工单
```

### 4.2 相对基线状态机机制（Baseline State Machine）

系统严禁将基线实现为无节制的“滑动平均”（防止“温水煮青蛙”式的失效漏报）。基线必须遵循状态机进行生命周期管理：

```
                 [新测点接入 / 设备级人工重置]
                           │
                           ▼
                 ┌───────────────────┐
                 │    0: 学习中       │ ◄────── 连续收集 M 个有效稳态样本
                 │    (LEARNING)     │        (通常持续 3~7 天, M >= 300)
                 └─────────┬─────────┘
                           │ 样本量达成且无严重离散 (CV < 0.25)
                           ▼
                 ┌───────────────────┐
       ┌─────────│    1: 生效锁定     │ ◄────── 长期保持恒定，作为相对劣化原点
       │         │    (ACTIVE)       │
       │         └───────────────────┘
       │ 设备检修 / 换件 / 变工况
       ▼
 ┌───────────────────┐
 │   2: 待重置确认   │ ────── 人工复核通过 / 触发学习 ──────┐
 │ (PENDING_RESET)   │                                     │
 └───────────────────┘                                     ▼
   ▲                                             返回 [0: 学习中]
   └──────────────── 现场异常工单驳回 ─────────────────────┘
```

* **基线计算公式（分位数抗噪模型）**：
  设有效稳态样本集为 $S = \{A_1, A_2, \dots, A_M\}$，排序后计算中位数（P50）：
  $$A_{base} = \text{Median}(S)$$
  同时计算样本离散系数（$CV = \frac{\sigma}{\mu}$），当 $CV \ge 0.35$ 时，说明设备处于强工况剧烈变动期，系统自动延长学习周期并发出“基线发散警告”。

#### 4.2.1 频谱基线参考带（谱域扩展，工作台可视化对比）

标量特征基线（P50/CV）解决"幅值正常吗"，但专家工作台的 FFT/包络谱是整条曲线——为让谱线有健康参照，系统按 **1/6 倍频程频带**（FFT 谱 2–2000 Hz、包络谱 2–500 Hz）对每帧入库波形快照聚合带内能量（带内 bin 幅值平方和开方），滚动窗口逐带复用 MAD 抗噪内核学习参考带：

$$Band_{ref} = P50 \pm 3 \times 1.4826 \times MAD$$

生命周期遵循与标量基线同一哲学（防"温水煮青蛙"），但冻结判据独立：

* **学习**：波形快照稀疏（阈值越限/定时巡检/专家召唤触发，无连续窗口可言），按自身滚动窗口样本数判定；原始波形载荷不携带转速，快照触发语义即为工况可信度兜底（`pdm.spectrum-baseline.minimum-samples`，默认 30 帧 / 30 天窗口）。
* **冻结**：样本达下限即逐带冻结 P50/MAD（`pdm_spectrum_band_state`），此后不再吸收新帧——参考带固定，谱线劣化不会被移动参考"追平"。
* **重置**：沿用标量基线的唯一重置路径（confirmReset），频谱样本与参考带一并清空重学。
* **消费**：`GET /api/v1/diagnostics/{pointId}/spectrum-baseline` 返回逐带上下限；工作站 FFT/包络视图叠加半透明参考带，超上界的带标红。另在谐波光标（1X/2X/3X）与 BPFO/BPFI 故障线处叠加特征基线 P50 短段并给出倍率 $k$ 读数（$k \ge k_{mid}=2.5$ 标红劣化），与 HI 相对衰减方程同口径。
* **边界**：MVP 仅做可视化对比与判读辅助，参考带超限暂不接入告警引擎（后续项）。

### 4.3 修正 Sigmoid 非线性健康度评价模型（Health Index Formulation）

健康度计算融合**相对基线恶化倍率**与 **ISO 10816 绝对物理限值**，严格执行双底线原则：

$$HI_{final} = \min(HI_{rel}, HI_{abs})$$

#### 1. 相对恶化衰减方程 ($HI_{rel}$)
设当前 1X 转频振幅为 $A_{curr}$，固化基线为 $A_{base}$，相对倍率 $k = \frac{A_{curr}}{A_{base}}$：

$$HI_{rel} = 
\begin{cases} 
100 - \left(\frac{k - 1.0}{k_{dead} - 1.0}\right) \times 5, & 1.0 < k \le k_{dead} \\
\frac{100}{1 + \left(\frac{k - 1.0}{k_{mid} - 1.0}\right)^\gamma}, & k > k_{dead} \\
100, & k \le 1.0 
\end{cases}$$

* **系统推荐超参数**：
  * 死区倍率 $k_{dead} = 1.15$（$15\%$ 以内的涨幅视作工况温差波动，微量扣除 $0\sim 5$ 分）。
  * 拐点半衰倍率 $k_{mid} = 2.50$（当振幅达到基线 $2.5$ 倍时，得分跌至 50 分临界线）。
  * 衰减陡度指数 $\gamma = 2.20$。

#### 2. ISO 10816 绝对限值熔断方程 ($HI_{abs}$)
以中型工业机组（Class II, 15kW~300kW）振动烈度为准绳：
* $A_{curr} \le 2.8\text{ mm/s}$：$HI_{abs} = 100$
* $2.8\text{ mm/s} < A_{curr} \le 4.5\text{ mm/s}$（黄色预警区）：
  $$HI_{abs} = 80 - \left(\frac{A_{curr} - 2.8}{4.5 - 2.8}\right) \times 30 \quad (50 \sim 80\text{ 分})$$
* $4.5\text{ mm/s} < A_{curr} \le 7.1\text{ mm/s}$（红色报警区）：
  $$HI_{abs} = 50 - \left(\frac{A_{curr} - 4.5}{7.1 - 4.5}\right) \times 30 \quad (20 \sim 50\text{ 分})$$
* $A_{curr} > 7.1\text{ mm/s}$（危险停机区）：强行压制为 $10$ 分。

### 4.4 设备层健康汇总与测点证据

设备是健康分析、告警和维护任务的主对象；测点是设备下独立的证据通道。每个测点仍使用自身安装位置的基线单独计算 HI，不能把不同位置的原始特征混在一起。

当前 MVP 采用可解释的保守汇总：设备 HI = 当前在线、数据质量合格且有效的测点 HI 最低值。这样单个危险位置不会被其他健康测点的平均值稀释。设备状态按该代表 HI 映射（HI ≥80 正常、50–<80 关注、20–<50 警告、<20 危险）；无有效测点评分时显示无数据或基线学习状态。

设备详情和设备告警保留全部测点的状态、HI、数据质量与新鲜度作为证据，并标识当前代表的最差有效测点。传感器异常、停机和数据过期影响覆盖度，但不会直接作为设备故障判据。当前代表分方法应在界面中明确显示；未来有经过验证的设备类型权重、故障模式和维修样本后，再扩展为类型专属评分，不直接改用算术平均。

### 4.5 统一遥测契约与模态插件（多模态底座）

平台遥测统一为版本化内部契约：`contract_version`（当前 2）+ `modality_type` + 工况（转速/负载率/稳态）+ 特征编码到有限数值的映射，`raw_snapshot_ref` 仅为可选元数据。质量校验、去重与乱序丢弃、工况分发、尖峰抑制、基线学习、HI 分档与告警防抖保留在共享内核；特征定义、工况门禁与物理解释由按 `modality_type` 唯一注册的插件提供（重复注册启动失败；未知模态、缺失必需特征、非有限数值的样本直接拒绝，不进入评分或告警）。

- 旋转振动（`ROTATING_VIB`）：旧 REST `/api/v1/telemetry` 与 MQTT `pdm/v1/...` 入口经适配器转换为本模态，载荷与行为保持不变；停机判据、尖峰键、ISO 10816 绝对分与规则诊断全部沿用。
- 电机电流（`MCSA`，试点）：特征为工频、转差率、负载率与工频边频带/载波幅值（dB）。仅稳态且负载在配置区间内的样本进入评分，否则记为 `CONDITION_REJECTED`（不可评估，不产生确定性结论）；相对分按边频带/载波幅值比相对基线计算（协议 2.1.0 载荷 `load_pct` 缺省时自动取工况负载）。绝对阈值与告警默认关闭（`pdm.mcsa.alerts-enabled=false`），完成健康/故障样本离线比对与现场试点验收后开启；届时启用候选阈值 54/40 dB（待现场校准的假设，不是通用诊断标准）并输出“转子导条损伤疑似”归因。

新增版本化入口：REST `/api/v2/telemetry` 与 MQTT `pdm/v2/{factory}/{device}/{point}/telemetry`。基线状态按测点 + 模态 + 特征编码管理（`pdm_baseline_feature_state`：P50/CV/样本数/状态）；振动基线仍以旧列与旧表为准并双写新表，迁移期保留旧数据读取能力；基线重置路径覆盖新旧存储。模态范围已扩展至高压断路器（`SWITCHGEAR_EVENT`）与工艺调节阀（`CONTROL_VALVE_PROCESS`，即多模态阶段的调节阀模态，见附录实施记录）；往复机械与结构模态等其余模态仍按「数据就绪 → 特征质量验证 → 插件离线评估 → 受控试点 → 启用告警」推进，不在首期范围。

---

## 5. 存储架构与数据库建模（DDL）

### 5.1 关系型数据库（MySQL 8.0 DDL）

```sql
-- 1. 设备资产表
CREATE TABLE `pdm_device` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '设备主键ID',
  `factory_id` VARCHAR(32) NOT NULL COMMENT '工厂编码',
  `device_code` VARCHAR(64) NOT NULL COMMENT '设备资产全局唯一编码',
  `device_name` VARCHAR(128) NOT NULL COMMENT '设备名称',
  `rpm_nominal` DECIMAL(8,2) NOT NULL DEFAULT 1500.00 COMMENT '额定转速(RPM)',
  `power_kw` DECIMAL(8,2) DEFAULT NULL COMMENT '电机功率(kW)',
  `iso_class` VARCHAR(16) NOT NULL DEFAULT 'CLASS_II' COMMENT 'ISO 10816机组类型分类',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1:正常在运, 2:检修中, 3:已报废',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_factory_device` (`factory_id`, `device_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备资产主表';

-- 2. 测点配置与基线表
CREATE TABLE `pdm_measurement_point` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '测点主键ID',
  `device_id` BIGINT UNSIGNED NOT NULL COMMENT '所属设备ID',
  `point_code` VARCHAR(64) NOT NULL COMMENT '测点编码(如 DE_RADIAL, NDE_AXIAL)',
  `point_name` VARCHAR(128) NOT NULL COMMENT '测点名称',
  `sensor_mac` VARCHAR(32) DEFAULT NULL COMMENT '绑定传感器物理MAC/SN',
  `v_base_rms` DECIMAL(6,3) DEFAULT NULL COMMENT '振动速度健康基线(mm/s)',
  `a_base_peak` DECIMAL(6,3) DEFAULT NULL COMMENT '加速度峰值基线(m/s2)',
  `temp_base` DECIMAL(5,2) DEFAULT NULL COMMENT '运行温度健康基线(℃)',
  `baseline_status` TINYINT NOT NULL DEFAULT 0 COMMENT '0:自学习中, 1:已生效锁定, 2:待人工重置',
  `learned_samples` INT NOT NULL DEFAULT 0 COMMENT '已累积稳态样本数',
  `baseline_locked_at` DATETIME DEFAULT NULL COMMENT '基线固化生效时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_point` (`device_id`, `point_code`),
  CONSTRAINT `fk_point_device` FOREIGN KEY (`device_id`) REFERENCES `pdm_device` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测点配置与基线管理表';

-- 3. 告警事件与工单表
CREATE TABLE `pdm_alert_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '告警主键ID',
  `point_id` BIGINT UNSIGNED NOT NULL COMMENT '所属测点ID',
  `device_id` BIGINT UNSIGNED NOT NULL COMMENT '所属设备ID',
  `alert_level` VARCHAR(16) NOT NULL COMMENT 'LEVEL: ATTENTION, WARNING, DANGER',
  `health_index` DECIMAL(5,2) NOT NULL COMMENT '触发时的健康得分',
  `dominant_factor` VARCHAR(64) NOT NULL COMMENT '主导故障诱因(1X不平衡/2X不对中/轴承高频等)',
  `trigger_amp` DECIMAL(6,3) NOT NULL COMMENT '触发时测得振幅(mm/s)',
  `base_amp` DECIMAL(6,3) NOT NULL COMMENT '基准振幅(mm/s)',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0:未处理, 1:处理中, 2:已闭环, 3:已抑制误报',
  `notified` TINYINT NOT NULL DEFAULT 0 COMMENT '0:未通知, 1:已外发Webhook',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_device_status` (`device_id`, `status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警事件记录表';
```

### 5.2 时序数据库（TDengine 3.x STable DDL）

```sql
-- 创建 PdM 专用时序数据库 (数据保留 365 天, 写入窗口 10 天)
CREATE DATABASE pdm_ts_data KEEP 365 DAYS 10;

USE pdm_ts_data;

-- 创建振动特征时序超级表 (Super Table)
CREATE STABLE stb_point_telemetry (
    ts TIMESTAMP,
    v_rms FLOAT,            -- 振动速度有效值 (mm/s)
    a_peak FLOAT,           -- 加速度峰值 (m/s^2)
    a_kurtosis FLOAT,       -- 峭度参数
    freq_1x_amp FLOAT,      -- 1X转频振幅 (mm/s)
    freq_2x_amp FLOAT,      -- 2X转频振幅 (mm/s)
    envelope_rms FLOAT,     -- 高频包络值 (轴承微裂纹能量)
    temperature FLOAT,      -- 表面温度 (℃)
    health_index FLOAT,     -- 实时健康得分 (0.0~100.0)
    status_code TINYINT     -- 状态编码 (0:健康, 1:关注, 2:警告, 3:危险, 4:传感器脱落)
) TAGS (
    factory_id VARCHAR(32),
    device_code VARCHAR(64),
    point_code VARCHAR(64)
);
```

---

## 6. 核心业务处理流程与防抖设计

### 6.1 异常数据清洗与脱落防护算法
在进入算法计算前，系统严格前置执行三层防护过滤，防止由于现场硬件干扰产生业务脏数据：

1. **零振动与脱落判定（Sensor Detachment）**：
   若当前采样 $v_{rms} < 0.05\text{ mm/s}$ 且表面温度接近环境室温，标记为 `DATA_CODE_OFFLINE`（设备停机）或 `DATA_CODE_DETACHED`（磁吸座掉落）。此状态下**直接跳过健康扣分逻辑，不上报故障告警**，仅在系统侧报“传感器通信失联/探头异常”。
2. **瞬时野值中值滤波（Spike Suppressor）**：
   连续维护一个长度为 3 的滑动窗口队列。当出现单一数据点比前后均值突变高出 $400\%$ 且随后立即回落时，判定为现场搬运重物碰撞或偶发工频电磁杂波，执行中值剔除。

### 6.2 告警防抖与收敛机制（Sliding Window Debounce）
为防止由于瞬时工况波动导致运维人员手机被告警信息轰炸，系统设计了滑动确认窗口机制：

```
                    【更新设备下某测点 HI】
                              │
                              ▼
            【用设备当前在线有效测点最低 HI 形成设备健康结论】
                              │
                              ▼
            【检查触发测点最近 3 次连续采样 HI < 50】
                              │
            ┌─────────────────┴─────────────────┐
            ▼                                   ▼
  [连续 3 次均低于 50 分]                [仅单次低于 50 分，后续恢复]
            │                                   │
            ▼                                   ▼
  【建立设备级告警与工单】               【设备级告警不成立】
            │                            (记录日志，抑制告警)
            ▼
  【按设备 + 级别 + 诊断规则合并】
    • 过去 4 小时内是否已有同设备告警？
    - 是：聚合合并，避免重复轰炸
    - 否：立即调用钉钉/微信 Webhook 发送卡片，生成维修工单
```

---

## 7. 非功能性设计与高可用保障（NFR）

### 7.1 性能与吞吐指标
* **并发处理能力**：MVP 单节点（4 核 CPU / 16G 内存）支持不少于 **5,000 个测点**同时在线（按 15 分钟上报一次特征值测算，平均入库吞吐量 $\approx 5.5\text{ records/sec}$，峰值计算负载 $< 15\%\text{ CPU}$）。
* **接口响应延迟**：前端健康全景总览首屏加载时间 $< 800\text{ ms}$；单测点 30 天时序特征走势图查询相应时间 $< 300\text{ ms}$（基于 TDengine 预聚合降采样特性）。

### 7.2 可用性与数据容灾
* **断网保护**：边缘网关本地持久化队列可抵御不少于 **72 小时**的现场局域网中断，网络连通后平稳回放重传，系统基于时序时间戳自动完成入库补全与事后健康追溯。
* **时序与冷热分离**：TDengine 时序数据按 365 天滚动保留，关系型数据库每月自动对已闭环的告警工单执行冷热归档，保障事务表轻量运行。

### 7.3 安全合规规范
* **传输链路加密**：边缘网关至平台层全部走 TLS 1.3 双向加密传输（MQTT over TLS / HTTPS），规避工厂内部嗅探风险。
* **访问隔离与凭据生命周期**：边缘端各网关分配独立的 MQTT 客户端证书与只读/写入 Topic 权限隔离，禁止越权订阅；业务平台密码采用 BCrypt 强哈希存储，严格实施 RBAC 权限控制。

---

## 附录：PHM 专家级升级实施记录（2026-11-23）

本系统已依据《工业设备预测性维护与健康管理系统（PHM）全栈系统设计说明书》完成升级，落地范围与设计条目对应关系：

| 文档条目 | 实现位置 | 说明 |
| :--- | :--- | :--- |
| §2.1 协议 2.0.0 慢遥测 | `telemetry/ProtocolTelemetryRequest`、MQTT `devices/+/+/telemetry` | asset_id→设备编码全局定位，metrics→平台特征码映射，温升派生 |
| §2.2 原始波形快照 | `waveform/` 包：MQTT `devices/+/+/raw_waveform`、`pdm_waveform_snapshot` | 越限/定时/召唤三类触发；preview 入 PG，原始数组归档本地卷（S3 扩展点预留）；原始波形仅经 MQTT 接入，无 REST 直推路由 |
| §3 边缘 DSP | `dsp/` 包（Fft/ButterworthBandpass/HilbertEnvelope/BandRms）+ 模拟器 `signal_features.py` | Band RMS ±3% 容差；4 阶巴特沃斯带通 + 希尔伯特解调；模拟器采样率升至 8192Hz 并修复冲击载波混叠 |
| §4.1 轴承几何库 | `bearing/` 包 + `pdm_bearing_library` 种子库 | BPFO/BPFI/BSF/FTF 几何解算与字典检索 `/api/v1/bearings/models` |
| §4.2 基线状态机 | 沿用既有 MAD+CV 状态机 | 扩展特征（BPFI/温升/3X/0.44X/sei/crest）经特征状态表参与收敛判定 |
| §4.3 劣化动力学 | `analytics/DegradationTracker` | β₁=dk/dt 最小二乘拟合，η_dyn=exp(−2.5(β₁−0.10)) 雪崩抑制 |
| §4.4 综合评分 | `analytics/HealthIndexCalculator` | 文档版 ISO 曲线 + 温升补偿 + BPFI + HI=min×η_dyn |
| §4.5 M-of-N 防抖 | 既有 `DeviceHealthWindow`（5 选 4，HI<50） | 与文档一致 |
| §6.2 确定性根因 | `analytics/DefectClassifier` | 雪崩→1X→2X→BPFO→BPFI→ISO→温升阶梯，写入告警 defect_detail |
| §7 API/WS | `AssetTreeController`、`TelemetryFeaturesController`、`DiagnosticsController`、`LiveStreamController`、`POST /api/v1/workorders` | SSE `/ws/v1/live-stream` 2Hz SPECTRUM_FRAME（谐波光标族 + 故障线 SNR） |
| §8 专家工作站 | `frontend/src/views/WorkbenchView.vue` | 时域卡尺/FFT 谐波光标/包络故障线三联动；告警诊断卡片与一键派单 |
| §9 DoD 验收 | `DspAcceptanceTest`（TC-01/02）、`BaselineStatisticsTest`（TC-03）、`DegradationTrackerTest`（TC-04）+ 模拟器 unittest | mvn test 全绿 |

范围决策：存储层保留 PostgreSQL + InfluxDB 3（与文档 MySQL/TDengine 架构角色一一对应），波形对象存储采用本地卷 + S3 扩展点，未引入 Redis/MinIO 容器；资产模型保持 工厂→设备→测点 三级并输出树形 API（WORKSHOP/LINE 可后续扩展）。

## 附录：多模异构数据处理实施记录（2026-11-23）

本系统已依据《工业设备预测性维护（PHM）多模态异构数据微内核系统设计说明书》（协议 2.1.0）完成升级，落地范围与设计条目对应关系：

| 文档条目 | 实现位置 | 说明 |
| :--- | :--- | :--- |
| §1 解耦模型（微内核 + 策略插件） | `modality/` 包：`MechanismPluginStrategy`/`MechanismStrategyPlugin`（SPI 与基类）、`ModalityPluginRegistry`（唯一注册启动校验）、`DiagnosticMicrokernel`、`TelemetryFreshnessPolicy` | 物理管道通用化：统一契约 + 按模态插件；`ModalityPlugin.cadence()`（CONTINUOUS/EVENT）、`staleAfter()`、`BaselineLearning`（windowed/pooled）为节律适配扩展点 |
| §2.1 慢周期特征遥测 | `telemetry/PhmTelemetryRequest`、MQTT `phm/telemetry/+/+`（`PDM_MQTT_PHM21_TOPIC`）、`POST /api/v2/phm/telemetry` | asset_id→设备编码、channel_id→测点编码全局定位，工厂经资产反查；`modality_type` 经 `ModalityTypes.normalize` 归一化（`ELECTRICAL_MCSA`→`MCSA`）；operating_condition 原始键全量保留并派生 rpm/load_pct/steady_state（兼容 speed_rpm/load_ratio/is_steady 别名）；metrics 非有限数值丢弃。文档 §2.1 旋转振动样例按原样接收：feat_1x/env_rms/temp_bearing 等指标名在平台码缺省时映射为平台特征码，派生温升并以工况桥接转速；该路径 a_peak 可选（缺省以 −1.0 哨兵入库、读回 null，V15 放宽基线样本列）；MCSA 的 load_pct 缺省取工况负载；ROTATING_VIB `is_steady=false` 按 `CONDITION_REJECTED` 拒绝 |
| §2.2 突发快照 | `snapshot/PhmSnapshotPayload`、MQTT `phm/snapshot/+/+`（`PDM_MQTT_PHM_SNAPSHOT_TOPIC`）、`POST /api/v2/snapshots`、`pdm_channel_snapshot`（V14；V16 去重 + UNIQUE） | trigger_type 白名单四类；preview_curve 上限同 `pdm.waveform.max-preview-points`；curves/curve_units/metrics JSONB 入库（最多 8 条 × 16384 点，health_index 限 0–100 有限数值），preview_curve 归档本地卷 `file://` 引用（`WaveformStorage` 扩展点），边缘 snapshot_url 单独保留；REST 直推时间戳 ±1 天（MQTT 容忍补传，>1 年拒绝）；同测点同 sampled_at 幂等重放；TRIP_ACTION_BURST/THRESHOLD_EXCEEDED 回链最近未关闭告警的 raw_snapshot_url；ROTATING_VIB/MCSA 测点快照 400（走波形通道） |
| §3.1 通道模型 | `V13__multimodal_channel.sql`：模态 CHECK 放宽为 `^[A-Z][A-Z0-9_]{0,31}$` 正则 + `sampling_rate`/`engineering_unit`/`metadata_config` 三列 | 测点管理 API 创建/编辑同步扩展（pluginIdentifier 仅校验不落库，按模态解析 `identifier()`）；文档 §3.1 的 EAV 基线语义由 `pdm_baseline_feature_state` + 测点 `metadata_config` 共同承担，未建独立通道表 |
| §4.1 通用微内核 | 沿用共享内核 + 事件型适配：`SpikeFilter` 事件直通、`DeviceHealthWindow` 事件型断档不清窗、`BaselineService` 池化学习（≥200 样本即可） | MAD 离群清洗（阈值 4.4478·MAD，按 3σ=1.4826·MAD 实现）、P50、CV≤0.15 锁定、M-of-N（5 选 4）对全模态不变；新鲜度/劣化窗口按模态经 `TelemetryFreshnessPolicy` 解析，`DeviceHealthRollup`/`DashboardService`/`AssetTreeController`/`FailurePredictionService` 均按模态取时限 |
| §4.2 插件 SPI | `modality/MechanismPluginStrategy`（modalityType/isOperatingSteady/processDiagnosis + PluginDiagnosisResult） | 签名与文档一致；`metadata_config` 作为第三参数传入，机理参数可按通道覆盖（如 duration_danger_ratio、deadband_attention_ratio） |
| §4.3 领域插件 | 既有 `analytics/RotatingVibrationPlugin`、`mcsa/`；新增 `switchgear/CircuitBreakerPlugin`（bean `switchgearEventPlugin`）、`controlvalve/ControlValvePlugin`（bean `controlValvePlugin`） | 断路器：分闸时间分级 1.10→60 分/1.20→25 分（硬限直通）+ 线圈电流 <0.80 基线→40 分，动作事件永远视为稳态；调节阀：死区分级 2.5→40 分/5.0→20 分 + 超声泄漏 0.2→65 分/0.5→30 分（硬限），仅 steady_state 评分；死区评分偏差见下节 |
| §4.4 内核调度 | `modality/DiagnosticMicrokernel` | 门禁 → 插件诊断评分 → η_dyn=exp(−2.5(β₁−0.10)) 雪崩抑制 → 分档 → M-of-N 防抖（硬限击穿直通），与文档一致 |
| §5 前端工作站 | `frontend/src/views/WorkbenchView.vue` 动态组件分发器 + `frontend/src/components/workbenches/`：`RotatingVibrationWorkbench`、`SwitchgearStrokeWorkbench`、`ControlValveHysteresisWorkbench`、`GenericMetricsWorkbench` | 统一外框（HI/基线摘要、跨模态劣化趋势、告警侧栏、v2 SSE）；Element Plus 片段以现有自研 UI 实现，未知模态兜底 GenericMetricsWorkbench 而非抛错；测点详情/总览/设备管理/导航多模化，创建表单按 `/api/v1/modalities` 目录渲染 |
| §6.1 REST 路由 | `ModalityCatalogController`（`GET /api/v1/modalities`）、`AssetTreeController`（`GET /api/v2/assets/tree`，PointNode 增 pluginIdentifier/samplingRate/engineeringUnit/metadataConfig/cadence）、`ChannelBaselineController`、`ChannelQueryController`、`ChannelSnapshotController`、`ValveLoopController` | `{code}` 为测点 ID 或全局编码，跨工厂重码 409；series 支持 1m/5m/15m/1h/6h/raw（raw 2000 行、聚合 5000 行上限）；回环窗口 >P7D 截断、非阀模态 200+UNSUPPORTED_MODALITY |
| §6.2 实时推流 | `ModalityStreamController`（`GET /ws/v2/stream?channelCode=`）+ `ModalityStreamHub` | SSE 命名事件 metrics_frame/stroke_event_frame/valve_loop_frame，载荷 snake_case + frame_type=METRICS_FRAME/STROKE_EVENT_FRAME/VALVE_LOOP_FRAME；振动通道 409 引导回 /ws/v1/live-stream；断路器标注（起动/分离/制动/总时长/峰值电流/满行程/最大速度）与调节阀回环量化由 `StrokeAnnotationCalculator`/`ValveLoopAnalyzer` 服务端解算 |
| §7 DoD 验收 | `multimodal/MultimodalAcceptanceTest`（TC-MM-01 包络 55 分 + 无冲击对照信号变体、TC-MM-02 断路器硬限直通 + M-of-N 变体、TC-MM-03 阀门死区 40 分、TC-MM-04 MAD 清洗锁定 + 4.4/4.5·MAD 边界用例）+ 插件边界/内核/快照/回环/流发布/契约序列化单测 | `mvn -o -Dskip.installnodenpm -Dskip.npm test` 313 个测试全绿（三轮评审修复后，含特征键校验、快照幂等、PROVISIONAL 基线、SSE 学习帧等新用例） |

### 范围决策与偏差

- **存储架构**：仍为 PostgreSQL + InfluxDB 3（`point_telemetry` 测量新增 `modality` 字段与按模态特征列，对应文档 TDengine 分模态 STable 角色）；快照归档沿用本地卷 + `WaveformStorage` 扩展点（对应文档 MinIO；报文 `storage_backend` 与快照 `data_quality` 均为边缘侧透传字段，平台不读取、不落库生效）；未引入 Redis，防抖滑动池保持在应用内存 + 设备健康窗口。
- **死区评分公式**：采用分级阈值（2.5→40 分 / 5.0→20 分）而非文档 §4.3 插件 C 的连续公式——文档 §7 DoD 的 TC-MM-03 要求 k=3.5 时恰好 40 分，示例连续公式给出约 13.8 分，按验收表为准。
- **断路器工况门禁**：断路器为动作事件型，`isOperatingSteady` 恒真（与文档 §4.3 插件 B 一致）；基线采用池化学习（≥200 样本即可，样本保留 P365D），不做时间窗口覆盖要求。
- **模态别名**：文档 `ELECTRICAL_MCSA` 归一化为平台既有 `MCSA`，不注册新插件。
- **frame 键名**：文档 §6.2 的 frame 示例键名以本仓库 snake_case 载荷为准（命名事件 + `frame_type` 大写值）；快照 `annotations` 内的标注键沿用 camelCase（如 `tripStartMs`、`deadbandEstimatePct`），与快照查询接口一致。
- **遗留：模拟器未升级**：`pdm_simulator_dashboard` 独立模拟器仍为协议 2.0.0 双通道发布，未实现 2.1.0 `phm/telemetry`/`phm/snapshot` 发布端；平台侧已可接收，可用 REST `POST /api/v2/phm/telemetry`、`POST /api/v2/snapshots`、MQTT 手工注入，或直接运行仓库自带演示脚本 `scripts/Publish-MultimodalSamples.ps1`（向本地 Mosquitto 持续发布 2.1.0 断路器/调节阀遥测与断路器快照）验证。
- **遗留：迁移未实跑**：本机无 Docker 环境，Flyway V13/V14 与 InfluxDB 新字段经静态审查 + 实体映射比对验证，未实际执行迁移。

### 审查与修复记录（2026-11-24）

三轮只读评审（后端共享内核与采集管线 / API 面与验收覆盖矩阵 / 前端工作台 UX）后完成修复，`mvn -o -Dskip.installnodenpm -Dskip.npm test` 313 个测试全绿。关键修复：

- **MQTT 毒消息加固**：载荷缺失身份字段、校验不通过的报文改为告警日志 + 丢弃，不再断连触发 QoS1 重投循环；路由前缀由配置主题派生，topic 身份比较不区分大小写；Broker 密码留空即匿名。
- **设计文档 §2.1 旋转振动样例按原样接收**：feat_1x/env_rms/temp_bearing 等指标名自动映射平台特征码、派生温升、工况桥接转速；`a_peak` 在 2.1.0 路径可选（哨兵 −1.0 入库，V15 放宽基线样本列）；MCSA `load_pct` 经工况桥接可用。
- **阀门硬限告警恢复**：超声泄漏 > 0.5 RMS 硬限击穿直接报 DANGER 并生成 HIGH 优先级工单，不再依赖防抖窗口。
- **基线收敛锁按收敛特征判定**：断路器/调节阀插件声明各自 `convergenceFeatureCodes`（如断路器 trip_duration_ms/peak_coil_current_a/closing_velocity_mps），仅统计出现于 ≥50% 窗口样本的特征 CV；全部特征照常学习存储。
- **η_dyn 按节律设拟合门槛**：事件型 ≥8 个动作且跨度 ≥1/12 窗口、连续型 ≥24 个样本（5 分钟抽稀）且跨度 ≥1/2 窗口，外加 ≥0.15 倍率区间门槛；噪声通道不再误报 DANGER，“劣化速率突增”归因不再附加到健康文本。
- **特征键白名单**：`^[a-z][a-z0-9_]{0,63}$` + 11 个平台保留名，违规 400 并点名键值；`metadata_config` 阈值覆盖按全局属性同款约束做范围校验。
- **迟滞回环接口修复**：`GET /api/v2/channels/{code}/hysteresis` 改为只读事务（阀门通道可用），`from`/`to` 返回首/末配对样本 epoch ms（空集为 null），新增 `truncated` 与 `limit: 20000`。
- **PROVISIONAL 基线预览**：`GET /api/v2/channels/{code}/baseline` 学习期现算 `status=PROVISIONAL` 行（窗口中位数/CV/样本数 ≥ 8，lockedAt 为 null），锁定前即可观察收敛。
- **SSE 提交后发布 + 学习帧**：`/ws/v2/stream` 每个被接受的非振动样本都产生 `metrics_frame`，学习/门禁/缓冲/尖峰抑制帧 `health_index`/`alarm_level` 为 null 并携带 `status`；帧在入库事务提交后发布，劣质样本不产帧，`annotations` 恒为 `{}`。
- **快照加固**：curves 上限 8 × 16384、health_index 限 0–100、REST 时间戳 ±1 天；重复 (point, sampled_at) 幂等重放（V16 去重 + 唯一约束）；`POST /api/v2/workorders` 路由补齐（设计 §6.1 五条 v2 路由完整）；series 接口新增 `truncated` 并校验时间窗边界。
- **演示种子与发布脚本**：`PDM_DEMO_DATA=true` 额外种出断路器/调节阀演示设备（各一条合成快照 + ACTIVE 基线），新增 `scripts/Publish-MultimodalSamples.ps1` 经本地 Mosquitto 发布 2.1.0 遥测/快照。
- **DoD 增补**：TC-MM-01 无冲击对照信号变体、TC-MM-04 的 4.4/4.5·MAD 清洗边界用例。

### 开放事项（待决策）

- `operating_condition` 的 bin_index 工况槽位未实现：附加键当前仅原样保留用于插件门禁读取，不做槽位化建模。
- 告警事件尚无 `event_id` 对外标识、`acknowledged_by` 归属字段；告警仅 WARNING/DANGER 两级，无 ATTENTION 级（设备 HI 分档保留 NORMAL/WARNING/DANGER 展示档）。
- 实时推流为 SSE（`/ws/v2/stream`），未升级到 WebSocket 双向通道。
- REST 直推入口复用浏览器会话 + CSRF 保护，无机器对机器令牌；边缘侧无法脱离浏览器会话调用（演示脚本因此走 MQTT）。
- 告警防抖窗口为应用内存态，应用重启后各设备窗口清零重新累计。
- 独立模拟器尚未发布 2.1.0 遥测/快照，演示数据以 `scripts/Publish-MultimodalSamples.ps1` 替代。
