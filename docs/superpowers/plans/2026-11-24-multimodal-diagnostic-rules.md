# 多模态设备主导因素规则实施计划

> **For agentic workers:** 当前 Codex 会话按任务顺序原生实施；尊重工作区现有未提交状态。

**Goal:** 将设备类型可配置主导因素规则扩展到 MCSA 电机、断路器和调节阀，并为演示类型补齐默认规则。

**Architecture:** 扩展现有 DiagnosticMetric/DiagnosticEvaluator，按 `deviceTypeCode` 校验和显示可用指标。AnalyticsEngine 在模态插件评分后调用通用规则匹配；只有配置规则命中且插件未报硬限时，替换诊断因素信息，不改分数。

**Tech Stack:** Java 21/Spring Boot、JPA/Spring Data、Vue 3/TypeScript、PostgreSQL 现有规则表。

**Spec:** `docs/superpowers/specs/2026-11-24-multimodal-diagnostic-rules-design.md`

## Global Constraints

- 健康评分、分档、M-of-N 防抖、硬限判断和 `PDM_MCSA_ALERTS_ENABLED=false` 默认值保持不变。
- 设备类别与测点模态分开；只按当前 3 个设备类别映射新指标。
- 默认规则只在设备类型没有任何已有规则时补齐；已有启用/停用/自定义规则均保留。
- 默认值沿用现有插件属性或设计值，标记为需现场校准。
- V18 扩展数据库规则指标 CHECK；默认规则初始化不依赖 `PDM_DEMO_DATA`，但只处理已经存在的三类设备类型。
- 不新增或运行测试；同步必要的现有测试编译接口。按开发者指示做 Java 编译、前端构建和静态检查。
- 不覆盖工作区里与本任务无关的现有更改。

## Review Focus

- 规则阈值使用的量纲：只有倍率指标做 current/base，触头磨损率直接比较 0–1 原始值。
- 缺失、NaN、Infinity、零/负基线不命中规则，不能中断评分。
- 规则匹配不得覆盖 `hardLimitBreached=true` 时的插件内置诊断。
- 匹配规则只改诊断快照字段，不改变健康指数、告警档位或 MCSA 告警开关。
- UI 和后端类别过滤一致；GENERAL/既有自定义类型只保留原振动规则指标。

---

### Task 1: 扩展规则指标与按模态求值

**Files:**
- Modify: `src/main/java/com/pdm/mvp/diagnostics/DiagnosticMetric.java`
- Modify: `src/main/java/com/pdm/mvp/diagnostics/DiagnosticEvaluator.java`
- Modify: `src/main/java/com/pdm/mvp/diagnostics/DiagnosticRuleService.java`

**Interfaces:**
- 新增 MCSA 间距/转差率；断路器分闸时间倍率/线圈电流倍率/合闸速度倍率/触头磨损原值；阀死区倍率/超声泄漏 RMS/行程滞后倍率/摩擦力倍率指标。
- `DiagnosticEvaluator.evaluateConfigured(PointEntity, TelemetryEnvelope, BaselineSnapshot)` 返回有序首个匹配的 `Optional<DiagnosticMatch>`。
- `DiagnosticMetric.supportedForDeviceType(String)` 统一后端类别适用性校验。

- [x] 实现类别指标集合、观测值映射、倍率安全计算、有限值检查和适用类别校验。
- [x] 在创建/更新规则时先解析设备类型并拒绝不适用的 metric。

### Task 2: 接入预测链路并保留硬限结果

**Files:**
- Modify: `src/main/java/com/pdm/mvp/analytics/AnalyticsEngine.java`

- [x] 插件评分完成后，对非旋转模态执行通用规则匹配。
- [x] 仅在匹配且 `hardLimitBreached=false` 时替换诊断字段；复制原评分与硬限状态，不重算分数。
- [x] 无规则命中时保留插件原诊断；MCSA 告警开关不变。

### Task 3: 按设备类别呈现适用指标

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/diagnostics.ts`
- Modify: `frontend/src/views/DiagnosticRulesView.vue`
- Modify: `frontend/src/components/DiagnosticRuleEditor.vue`

- [x] 增加新 metric 类型、中文名称和单位。
- [x] 将 `typeCode` 传入规则编辑器，按类别筛选 metric；新增规则默认选择该类型首个适用指标。
- [x] 保存编辑时维持既有条件格式化与校验。

### Task 4: 幂等补齐三类演示规则

**Files:**
- Modify: `src/main/java/com/pdm/mvp/diagnostics/DiagnosticRuleService.java`
- Create: `src/main/java/com/pdm/mvp/diagnostics/DefaultDiagnosticRuleInitializer.java`
- Modify: `src/main/java/com/pdm/mvp/asset/DemoDataInitializer.java` (ensure assets run before default-rule initializer)
- Modify: `src/test/java/com/pdm/mvp/asset/DemoDataInitializerTest.java` (同步既有用例)

**Default Rules:**
- MCSA: `sideband_gap_db <= warn` 与 `<= danger`，使用 `McsaProperties` 当前默认阈值。
- Breaker: `trip_duration_ratio >= attention` 与 `coil_current_ratio <= current_attention`，使用 `SwitchgearProperties` 当前默认值；`> danger` 由插件硬限诊断优先。
- Valve: `deadband_ratio >= attention/danger` 与 `leak_rms > attention`，使用 `ControlValveProperties` 当前默认值；硬限诊断由插件优先。

- [x] 仅当类型下不存在任何规则时插入该类型默认规则，稳定优先级保证严重规则先匹配。
- [x] 保留用户手工创建、编辑和禁用的规则。
- [x] 更新既有种子断言对应的类型规则配置，不新增测试。

### Task 6: 放宽数据库多模态指标约束

**Files:**
- Create: `src/main/resources/db/migration/V18__expand_multimodal_diagnostic_metrics.sql`
- Create: `src/main/resources/db/migration/V19__validate_multimodal_diagnostic_metrics.sql`
- Modify: `README.md`
- Modify: `pdm_simulator_dashboard/pdm-simulator/README.md`

- [x] 用 V18/V19 两个提交迁移扩展并验证 V4 的振动指标白名单约束，保留非负阈值与阶次校验约束。
- [x] 记录默认规则初始化不依赖 `.env` 注入到 IDE 进程，只需类别存在。
- [x] 编译并构建前端；不运行测试。

### Task 5: 更新文档并静态验证

**Files:**
- Modify: `README.md`
- Modify: `pdm_simulator_dashboard/pdm-simulator/README.md`
- Modify: `docs/superpowers/specs/2026-11-24-multimodal-diagnostic-rules-design.md`

- [x] 记录各类型可用指标、默认因素规则与候选阈值校准说明。
- [x] `mvn -q -DskipTests test-compile`、前端 production build、TS enum/static checks；不运行测试。
