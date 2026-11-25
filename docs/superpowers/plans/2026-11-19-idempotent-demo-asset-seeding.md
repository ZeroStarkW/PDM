# 幂等补齐演示资产实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** `PDM_DEMO_DATA=true` 时，即使工厂或设备已存在，也补齐 `FACT_01 / PUMP_P102A / DE_RADIAL` 演示资产链。

**Architecture:** 只改 `DemoDataInitializer`，在单一 PostgreSQL 事务中获取事务级 advisory lock，再按工厂、设备、测点逐级查询；仅在缺失时创建，复用现有仓储与实体。

**Tech Stack:** Java 21、Spring Boot、Spring Data JPA、PostgreSQL。

**Spec:** `docs/superpowers/specs/2026-11-19-idempotent-demo-asset-seeding-design.md`

## Global Constraints

- 保留现有工厂、设备和测点记录及其配置。
- 演示资产补齐仅在 `PDM_DEMO_DATA=true` 时执行。
- 不改变通用设备创建或 MQTT 遥测校验行为。

## Review Focus

- 已有工厂但没有演示设备：仅新增设备与测点，不重复创建工厂。
- 已有设备但没有测点：仅新增测点，不覆盖设备配置。
- 演示资产已完整：重启不重复创建或修改记录。
- 其他工厂或同码设备：不得被选中或修改。

---

### Task 1: 幂等补齐演示资产

**Files:**
- Modify: `src/main/java/com/pdm/mvp/asset/DemoDataInitializer.java`

**Interfaces:**
- Consumes: `FactoryRepository.findByFactoryCode(String)`、`DeviceRepository.findByFactory_FactoryCodeAndDeviceCode(String, String)`、`PointRepository.findByDevice_Factory_FactoryCodeAndDevice_DeviceCodeAndPointCode(String, String, String)`。
- Produces: 演示模式启动后确保存在 `FACT_01 / PUMP_P102A / DE_RADIAL`，且不会重复创建。

- [x] 将当前“工厂已存在即直接返回”的逻辑替换为逐级幂等查找；编码固定为 `FACT_01`、`PUMP_P102A`、`DE_RADIAL`，名称和设备参数沿用现有演示数据值。
- [x] 对每个缺失层级只创建该层记录；创建测点前先解析对应演示设备，确保关联关系正确。
- [x] 用 PostgreSQL 事务级 advisory lock 串行化并发初始化；检查代码差异并运行项目构建验证编译通过；不添加或运行测试。
