# PdM MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a runnable, single-deployment PdM MVP for rotating equipment using JDK 21, Spring Boot 4, Vue 3, PostgreSQL, and InfluxDB 3.

**Architecture:** One Maven Spring Boot application provides versioned REST APIs and serves the Vue/Vite build from its executable JAR. PostgreSQL owns transactional data; InfluxDB 3 stores telemetry and health time series behind an adapter. Telemetry flows through validation, filtering, baseline/HI analysis, time-series persistence, then alert debounce and work-order creation.

**Tech Stack:** JDK 21; Maven; Spring Boot 4.1.2; Spring MVC (`spring-boot-starter-webmvc`); Spring Data JPA; Spring Security; Flyway; PostgreSQL; InfluxDB 3 Java client 1.11.0; optional MQTT consumer; Vue 3 + TypeScript + Vite + Pinia + Vue Router + ECharts; Docker Compose.

**Spec:** `docs/superpowers/specs/2026-11-19-pdm-mvp-design.md`

## Global Constraints

- JDK 21、Spring Boot 4、Vue 3、PostgreSQL、InfluxDB 3。
- Spring Boot 单体部署：一个应用进程提供 REST API 和 Vue 静态页面；前端仍使用 Vue 组件和路由。
- PostgreSQL 为事务数据唯一权威源；InfluxDB 3 为遥测/健康度时间序列存储，数据库名为 `pdm-ts-data`，保留期为 365 天。
- 前端使用 Vue 3、TypeScript、Pinia、Vue Router、ECharts；构建工具采用 Vite。
- 后端使用 Spring MVC、Spring Data JPA、Bean Validation、Spring Security、Flyway；密码使用 BCrypt。
- InfluxDB 3 使用官方 v3 Java 客户端（当前文档版本 1.11.0）。
- MQTT 消费需可通过配置启用/禁用；应用在本地开发环境无 MQTT Broker 时仍可启动。
- 保持工作区现有 `pdm_simulator_dashboard/pdm-simulator` 独立运行，仅通过 MQTT topic/payload 接入；不改动其源码。
- 保持 MQTT topic 与 payload 契约、HI/基线参数、三次告警确认、四小时冷却和角色权限与设计一致。

## Review Focus

- 时间戳过期、超前或非数值遥测不得进入 HI 计算；在遥测接入阶段检查边界时间、NaN/Infinity 和负值输入。
- `data_quality != 0` 或 `v_rms < 0.05` 的测量不得污染基线或触发故障工单；在遥测流程阶段检查拒绝/记录行为。
- 1X 相对项使用 `freq_1x_amp`、ISO 绝对项使用 `v_rms`，HI 为两者最小值；在算法阶段逐阈值检查评分输入和状态映射。
- ACTIVE 基线不可被常规遥测自动漂移；在基线阶段检查锁定、变异系数阈值和人工确认重置。
- 单次 HI <50 不可告警，连续三次才确认，四小时冷却内不可重复开单；在告警阶段检查序列和冷却边界。

---

## File Structure

- `pom.xml` — Spring Boot parent/dependencies, Java 21 compiler, frontend build integration, executable JAR packaging.
- `src/main/java/com/pdm/mvp/` — application, `config`, `security`, and focused `asset`, `telemetry`, `analytics`, `alert`, `workorder`, `timeseries` packages.
- `src/main/resources/application.yml` and profiles — environment-driven Postgres, InfluxDB, MQTT, session, and actuator settings.
- `src/main/resources/db/migration/` — ordered PostgreSQL Flyway schema and seed migrations.
- `src/main/resources/static/` — generated Vue application output; never hand-edit generated assets.
- `frontend/` — Vue/Vite application, views, reusable components, typed API client, stores and router.
- `compose.yaml`, `.env.example`, `.gitignore`, `README.md` — local services, secrets template, generated/build output exclusions, setup and payload examples.

## Task 1: Buildable Single-Artifact Skeleton and Local Infrastructure

**Files:** Create `pom.xml`, `src/main/java/com/pdm/mvp/PdmApplication.java`, `src/main/resources/application.yml`, `frontend/package.json`, `frontend/vite.config.ts`, `frontend/src/main.ts`, `frontend/src/App.vue`, `compose.yaml`, `.env.example`, `.gitignore`.

**Interfaces:** Produces `com.pdm.mvp.PdmApplication` as the application entry point; Vite writes the UI to `src/main/resources/static`; environment keys are `PDM_DB_URL`, `PDM_DB_USERNAME`, `PDM_DB_PASSWORD`, `PDM_INFLUX_URL`, `PDM_INFLUX_TOKEN`, `PDM_INFLUX_DATABASE`, and `PDM_MQTT_ENABLED`.

- [ ] Create Maven parent/dependencies for Spring Boot 4.1.2, Java 21, WebMVC, JPA, Validation, Security, Actuator, PostgreSQL, Flyway, InfluxDB 3 Java 1.11.0, and optional MQTT integration; configure Vite install/build before Maven packaging.
- [ ] Add Vue 3 + TypeScript Vite shell, `/api` dev proxy, and generated static output path.
- [ ] Add Docker Compose services for PostgreSQL and InfluxDB 3 Core (`influxdb:3-core`) with health checks and named data volumes; provision the Influx admin token from an ignored local secret file; application and MQTT consumer settings are environment-configurable.
- [ ] Add `.env.example` with non-secret local placeholders and ignore `.env`, local Influx token file, Maven output, `node_modules`, and generated frontend bundles.
- [ ] Run `mvn -DskipTests package` with the documented prerequisites; confirm executable JAR contains `static/index.html` and starts the embedded MVC server.

## Task 2: PostgreSQL Schema, Authentication, and Asset APIs

**Files:** Create `src/main/resources/db/migration/V1__core_schema.sql`, `.../V2__auth_and_baseline_samples.sql`, `asset/FactoryEntity.java`, `asset/DeviceEntity.java`, `asset/PointEntity.java`, repositories, DTOs, services, controllers, `security/SecurityConfiguration.java`, `security/AuthController.java`, and bootstrap admin initializer.

**Interfaces:** Exposes asset REST resources under `/api/v1/factories`, `/api/v1/devices`, `/api/v1/points`; provides `AssetLookup.requirePoint(factoryId, deviceCode, pointCode) -> PointEntity`; login returns a server session and `/api/v1/auth/me` returns current role details. Entities use PostgreSQL identity BIGINT IDs and `timestamptz` instants.

- [ ] Define V1 Flyway tables/indexes for factories, devices and points; define V2 tables/indexes for baseline samples, users, roles and user-role links; enforce unique factory/device and device/point codes with foreign keys.
- [ ] Implement ADMIN-only mutations and VIEWER/OPERATOR/ADMIN reads for asset resources, with uniqueness and referential validation.
- [ ] Implement ADMIN, OPERATOR, VIEWER roles; bootstrap first ADMIN only when explicit username/password environment values are present; BCrypt passwords; session cookie with CSRF protection.
- [ ] Expose session login/logout/current-user endpoints and map authorization failures to consistent JSON API errors; user/role administration UI is out of MVP scope.
- [ ] Build the application package without tests and confirm Flyway applies cleanly to a fresh Postgres database.

## Task 3: InfluxDB Adapter and Telemetry Ingestion

**Files:** Create `telemetry/TelemetryRequest.java`, `TelemetryController.java`, `TelemetryService.java`, `TelemetryValidator.java`, `TelemetryResult.java`, `timeseries/TelemetryStore.java`, `timeseries/InfluxTelemetryStore.java`, MQTT properties/config/handler classes, and `src/main/resources/db/migration/V3__alerts_work_orders.sql`.

**Interfaces:** `TelemetryRequest` mirrors the documented JSON fields; `TelemetryService.accept(TelemetryRequest request, String source) -> TelemetryResult`; `TelemetryStore.write(TelemetryPoint point)` and `TelemetryStore.query(pointId, from, to, interval) -> List<TelemetryPoint>`; REST entry point is `POST /api/v1/telemetry`.

- [ ] Validate timestamp within ±5 minutes, data-quality code range, finite numeric values, nonnegative physically valid measurements, optional temperature/envelope/top peaks, and existing factory/device/point mapping; ignore unknown telemetry extension fields such as the simulator's health_index; MQTT topic identifiers must equal payload identifiers.
- [ ] Persist accepted telemetry to InfluxDB 3 database `pdm-ts-data` (365-day retention), measurement `point_telemetry`, documented tags and fields plus `top_peaks_json`; use bounded asynchronous batching, timeouts, and observable failures.
- [ ] Preserve invalid quality samples with a quality/status code when identity and timestamp are valid; exclude them from analysis. Persist low-vibration samples as stopped/sensor-check state and exclude from fault scoring.
- [ ] Add optional MQTT subscription using the `pdm/v1/+/+/+/telemetry` topic and QoS 1; when `PDM_MQTT_ENABLED=false`, create no broker connection and keep the app startable.
- [ ] Build/package and inspect API handling plus InfluxDB writes/queries against the local compose services.

## Task 4: Baseline State Machine and Health Index

**Files:** Create `analytics/BaselineService.java`, `BaselineStatistics.java`, `HealthIndexCalculator.java`, `HealthAssessment.java`, `SpikeFilter.java`, `PointHealthState.java`, and `baseline` API/controller DTOs.

**Interfaces:** `BaselineService.observe(PointEntity point, TelemetryRequest sample) -> BaselineState`; `BaselineService.requestReset(pointId)` and `confirmReset(pointId, actor)`; `HealthIndexCalculator.calculate(freq1xAmp, freq1xBase, vRms) -> HealthAssessment` returns `hiRelative`, `hiAbsolute`, `healthIndex`, and level; analytics output is consumed by `TelemetryService` before timeseries write.

- [ ] Keep up to three valid samples per point for the 400% spike rule; retain raw time-series data but mark an excluded spike so it cannot train baseline or trigger alerts.
- [ ] Collect at least 300 samples with `data_quality=0` and `v_rms >=0.05` as the available steady-state proxy; calculate separate P50 baselines for `freq_1x_amp` (`freq_1x_base`) and `v_rms` (`v_base_rms`) plus CV; lock only when CV <0.25; issue divergence warning for CV >=0.35; never auto-drift ACTIVE baseline.
- [ ] Implement LEARNING → ACTIVE and ACTIVE → PENDING_RESET → LEARNING transitions, with reset confirmation restricted to ADMIN and state changes persisted in PostgreSQL.
- [ ] Implement exact relative HI formula with `k_dead=1.15`, `k_mid=2.50`, `gamma=2.20`; calculate relative score from `freq_1x_amp` and `freq_1x_base`.
- [ ] Implement absolute score from `v_rms`: <=2.8 => 100; (2.8,4.5] interpolate 80 to 50; (4.5,7.1] interpolate 50 to 20; >7.1 => 10; final score is the minimum. Mark HI unavailable until baseline is ACTIVE.
- [ ] Build/package and manually inspect calculations at all boundaries and baseline state transitions using representative payloads.

## Task 5: Debounced Alerts, Work Orders, and Dashboard Aggregation

**Files:** Create `alert/AlertEntity.java`, `AlertRepository.java`, `AlertService.java`, `AlertController.java`, `workorder/WorkOrderEntity.java`, `WorkOrderService.java`, `WorkOrderController.java`, `analytics/PointHealthWindow.java`, and `alert/WebhookNotifier.java`.

**Interfaces:** `AlertService.observe(pointId, TelemetryRequest sample, HealthAssessment assessment) -> Optional<AlertEntity>`; `WorkOrderService.createFromAlert(alert) -> WorkOrderEntity`; endpoints are `/api/v1/alerts`, `/api/v1/work-orders`; overview endpoint is `GET /api/v1/dashboard/overview`.

- [ ] Store alert event, trigger amplitudes, baseline amplitude, HI, dominant factor, status, notification state, and timestamps; infer the suggested inspection direction deterministically from 1X/2X dominance, high-frequency peaks, envelope RMS and kurtosis; store linked work order, assignee, lifecycle status, resolution note, and close time.
- [ ] Create fault alert only after three consecutive valid running samples below HI 50; map HI <20 to DANGER and 20–<50 to WARNING; HI 50–<80 is dashboard attention only.
- [ ] Enforce four-hour same-point/same-level cooldown; aggregate repeated events to the active alert without duplicate work orders; record webhook result without blocking ingestion.
- [ ] Implement alert acknowledgement/suppression, work-order assignment/closure, role checks, and dashboard summary grouped by factory/device/health state.
- [ ] Build/package and exercise sequence/cooldown transitions against local Postgres and InfluxDB using supplied telemetry payloads.

## Task 6: Vue Operations Interface

**Files:** Create `frontend/src/api/http.ts`, `frontend/src/api/*.ts`, `frontend/src/router/index.ts`, `frontend/src/stores/auth.ts`, `frontend/src/layouts/AppLayout.vue`, views for Login, Dashboard, Devices, PointDetail, Alerts, WorkOrders, Baseline, and shared status/table/chart components.

**Interfaces:** API modules consume `/api/v1` JSON endpoints; Pinia auth store exposes `login`, `logout`, `loadCurrentUser`, and `hasRole`; router guards authenticated pages and role-gated actions; charts consume normalized `TelemetryPoint[]` returned by backend.

- [ ] Implement login, session restoration, logout, and role-aware navigation/actions.
- [ ] Implement health overview with factory/device counts and status color matrix; provide loading, empty, and error states.
- [ ] Implement asset/device/point browse and detail, ECharts telemetry trend with time range selection, and current baseline/health summary.
- [ ] Implement alerts and work-order lifecycle screens, including acknowledge/suppress, assignment/close, and resolution notes.
- [ ] Implement baseline state and reset request/confirmation controls restricted to ADMIN.
- [ ] Run Vite production build and package the UI into Spring static resources; verify SPA routes fall back to index while `/api/**` remains JSON.

## Task 7: Demo Data, Operations Documentation, and Final Packaging

**Files:** Create `src/main/resources/db/migration/V4__demo_data.sql` or an opt-in `DemoDataInitializer.java`, `README.md`, `docs/api/telemetry-samples.md`; modify Compose health checks and application profiles as needed.

**Interfaces:** Demo data is activated only by `PDM_DEMO_DATA=true`; documents show local prerequisites, environment configuration, startup, login bootstrap, REST/MQTT examples, InfluxDB query data, and restart/volume behavior.

- [ ] Add opt-in sample factory, devices, points, ADMIN bootstrap guidance, and valid telemetry sample; never insert demo credentials by default.
- [ ] Document JDK 21, Maven, Node.js 22.12+, Influx admin token bootstrap, database creation as `pdm-ts-data` with 365-day retention, `docker compose up -d postgres influxdb3`, `.env` setup, Maven package/run, and `mvn spring-boot:run` developer loop.
- [ ] Document MQTT topic/payload and REST telemetry, quality codes, baseline learning, HI bands, alert debounce, roles, known MVP exclusions, and how to point the simulator at the local broker/topic.
- [ ] Build final executable JAR from a clean checkout and verify the app serves both login page and API while connected to local Postgres/InfluxDB 3.

## Coverage Check

- Documented telemetry schema, quality filtering, timestamps and MQTT topic: Task 3.
- PostgreSQL asset/baseline/alert/work-order schema and MySQL-to-PostgreSQL adaptation: Tasks 2, 3, 5.
- Baseline lifecycle, P50/CV thresholds and reset approval: Task 4.
- HI equations and ISO absolute thresholds: Task 4.
- Three-sample alert debounce and four-hour cooling: Task 5.
- Vue health overview, trend, alerts and workflow screens: Task 6.
- RBAC, BCrypt, TLS-ready environment configuration, deployment and retention notes: Tasks 1, 2, 7; production mTLS and archival remain documented exclusions.
- No raw-waveform edge FFT, predictive ML, or production scale claim: explicit non-goals in the design.
- Existing simulator interoperability without modifying `pdm_simulator_dashboard/pdm-simulator`: Tasks 3 and 7.
