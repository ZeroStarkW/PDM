# PdM MVP 系统设计

## 意图

为流程制造现场的旋转机械提供一套可启动、可演示并可继续演进的预测性维护 MVP。系统管理员维护工厂、设备与测点，平台接收测点遥测并按设备自身健康基线计算健康指数（HI），通过连续采样防抖生成告警与维修工单，并在 Vue 看板中呈现资产状态和历史趋势。

## 目标

- 实现资产/测点管理、遥测接入、基线状态管理、HI 计算、告警防抖、工单闭环和全厂概览。
- 提供可运行的本地开发/演示部署，包括 PostgreSQL、InfluxDB 3 和应用的配置示例。
- 支持 MQTT 上行协议 `pdm/v1/{factory_id}/{device_code}/{point_code}/telemetry`；提供 REST 遥测入口，便于无现场 Broker 时调试和演示。
- 用关系库保存资产、配置、基线样本、告警、工单和用户/角色；InfluxDB 3 保存遥测和 HI 时序数据。
- 页面由 Spring Boot 同一应用提供，Vue 构建产物纳入可执行包。
- 兼容工作区现有的 `pdm_simulator_dashboard/pdm-simulator` MQTT 模拟器；模拟器作为独立现场数据源运行，不纳入平台进程或代码包。

## 非目标

- 不实现边缘网关固件、FFT 和原始波形处理；平台按文档接收边缘计算后的结构化特征。
- 不实现企业微信/钉钉凭据管理和正式消息发送；先提供可配置 Webhook 通知接口/适配器及本地日志型实现。
- 不承诺在未经压测的 MVP 中达到 5,000 测点、800ms 首屏或 300ms 查询指标。
- 不实现机器学习训练、复杂多租户隔离、企业级 SSO 或生产级高可用集群。

## 约束

- JDK 21、Spring Boot 4、Vue 3、PostgreSQL、InfluxDB 3。
- Spring Boot 单体部署：一个应用进程提供 REST API 和 Vue 静态页面；前端仍使用 Vue 组件和路由。
- PostgreSQL 为事务数据唯一权威源；InfluxDB 3 为遥测/健康度时间序列存储，数据库名为 `pdm-ts-data`，保留期为 365 天。
- 前端使用 Vue 3、TypeScript、Pinia、Vue Router、ECharts；构建工具采用 Vite。
- 后端使用 Spring MVC、Spring Data JPA、Bean Validation、Spring Security、Flyway；密码使用 BCrypt。
- InfluxDB 3 使用官方 v3 Java 客户端（当前文档版本 1.11.0）；官方客户端要求 Java 17+，可运行于 JDK 21。客户端通过 Arrow Flight/gRPC 查询，需在最终 Spring Boot 可执行 JAR 中验证服务资源加载。
- Spring Boot 官方系统要求支持 Java 17+；JDK 21 满足要求。
- InfluxDB 3 Core 数据库名只允许字母数字和中划线；本项目使用 `pdm-ts-data`。365 天保留策略在建库时设为 `365d`，Core 中建库后不可修改；本地部署通过 InfluxDB 管理令牌完成数据库初始化。
- MQTT 消费需可通过配置启用/禁用；应用在本地开发环境无 MQTT Broker 时仍可启动。

## 方案选择

1. **单体包内嵌 Vue 静态资源（选择）**：Vite 将 Vue 应用构建到 Spring Boot 静态目录，再由 Spring MVC 提供页面和 API。部署与配置只有一个应用制品，符合“前后端不分离”的发布要求。
2. 同一容器中使用反向代理分别提供前端和后端：允许独立调优，但增加代理配置与运行组件；当前 MVP 没有独立扩缩前后端的需求。
3. Thymeleaf 页面中嵌入 Vue 小组件：降低前端构建复杂度，但不适合 Vue Router、Pinia 和 ECharts 主导的完整交互看板。

## 系统组成与边界

- **Web/API 层**：Spring MVC REST API；JSON 统一响应、参数校验、分页、异常映射；同域提供 Vue 页面。
- **认证与授权**：登录会话与 BCrypt 用户凭据；MVP 提供 `ADMIN`、`OPERATOR`、`VIEWER` 三种角色。资产/配置与基线操作要求 ADMIN，工单处理要求 OPERATOR 或 ADMIN，查询允许 VIEWER。系统不开放自助注册；初始管理员通过环境变量引导创建。
- **资产域**：工厂、设备和测点；设备编码在工厂内唯一，测点编码在设备内唯一。
- **基线数值**：对 `freq_1x_amp` 与 `v_rms` 分别收集至少 300 个有效稳态样本，并分别固化 P50 基线 `freq_1x_base` 与 `v_base_rms`；HI 相对项使用前者，ISO 绝对项使用后者。
- **遥测域**：同一标准 DTO 支持 MQTT 和 REST 两种入口；按 `factory_id/device_code/point_code` 定位测点，完成时间戳、质量码、数值范围和设备/测点存在性校验。允许模拟器未提供的温度、包络 RMS、顶部特征峰为空；忽略模拟器附带的非协议扩展字段 `health_index`。
- **分析域**：运行/停机判断，三样本中值尖峰抑制，基线状态机，HI 评分，健康状态归类。
- **告警/工单域**：最近 3 次有效采样 HI 均低于 50 才确认告警；同测点/级别 4 小时冷却合并；确认告警创建工单，工单可分派、处理、闭环或标记误报。
- **存储适配层**：PostgreSQL 保存事务实体；InfluxDB 3 保存 `point_telemetry` 测量，标签包含工厂、设备、测点编码，字段保存 v_rms、a_peak、峭度、1X/2X、包络 RMS、温度、HI 和状态码。
- **前端页面**：登录；全厂总览；设备/测点列表与详情；测点历史趋势；告警列表/详情；工单流转；基线查看与重置确认。初始管理员通过环境变量引导创建，MVP 暂不提供用户/角色管理页面。

## 核心规则

- 遥测时间戳为 Unix 毫秒，允许与服务器时间相差最多 5 分钟；`data_quality != 0` 的样本存档并标记质量状态，不参与基线学习与 HI 告警。
- `v_rms < 0.05 mm/s` 作为停机/脱落类数据状态处理，不进入 HI 扣分与设备故障告警；仅记录传感器/停机状态。当前 payload 没有可靠区分停机与探头脱落所需的环境温度字段，页面使用“低振动/需检查传感器”综合状态，不伪造细分结论。
- 野值过滤以每测点最近三个有效样本为窗口；只有中间值比前后值均高出 400% 且随后回落时，将该中间值排除在基线和告警判定之外，但仍保留原始测量供追溯。
- 新测点基线初始为 LEARNING，累计至少 300 个有效运行样本。当前 MQTT 报文没有转速稳定标志，因此 MVP 暂以 `data_quality=0` 且 `v_rms >= 0.05` 作为有效稳态代理；用 P50 作为基线值；CV < 0.25 才允许锁定为 ACTIVE；CV >= 0.35 时继续学习并生成基线发散提示；0.25 <= CV < 0.35 时继续学习但不发散告警。锁定基线不自动滑动更新。
- ACTIVE 基线可申请进入 PENDING_RESET；只有人工确认后才重回 LEARNING，历史告警和时序数据保留。
- HI 相对项使用 `freq_1x_amp` 与 1X 基线计算；ISO 10816 Class II 绝对项使用 `v_rms` 与 2.8、4.5、7.1 mm/s 区间计算；最终 `min(HI_rel, HI_abs)`。超参数 `k_dead=1.15`、`k_mid=2.50`、`gamma=2.20`。停机/脱落状态不计算故障 HI。
- 告警等级按 HI 映射：HI >= 80 正常，50–<80 关注，20–<50 警告，<20 危险。当前建议“关注”只用于健康看板/测点趋势显示，不创建告警或工单；故障告警防抖仅在连续三次有效运行样本 HI < 50 时触发。相同测点和等级在 4 小时内已有未完成/已通知告警时不重复建单，可追加计数/最近时间。
- 文档里存储 SQL 为 MySQL DDL，实施时转换为 PostgreSQL 兼容实体与 Flyway 迁移；ID 用 BIGINT identity，时间统一使用带时区时间戳。

## 主要 API 边界

- `POST /api/v1/telemetry`：提交标准遥测 DTO，响应接受/拒绝及计算出的健康状态。
- `GET/POST/PUT /api/v1/factories|devices|points`：资产与测点查询维护。
- `GET /api/v1/dashboard/overview`：工厂/设备健康汇总。
- `GET /api/v1/points/{id}/telemetry?from=&to=&interval=`：查询时间序列趋势。
- `GET /api/v1/alerts`、`GET /api/v1/alerts/{id}`；`POST /api/v1/alerts/{id}/acknowledge|suppress`。
- `GET /api/v1/work-orders`、`POST /api/v1/work-orders/{id}/assign|close`。
- `POST /api/v1/points/{id}/baseline/reset-request`、`POST /api/v1/points/{id}/baseline/confirm-reset`。
- `POST /api/v1/auth/login`、`POST /api/v1/auth/logout`、`GET /api/v1/auth/me`。
- MQTT adapter 接受与 REST 相同 payload，并以 topic 编码核对 payload 中工厂/设备/测点编码。

## 交付与部署

- Maven 后端项目，Vue 前端源码位于同仓库并由 Maven/Vite 集成构建；`mvn package` 产出含页面资源的 Spring Boot 可执行 JAR。
- Docker Compose 提供 PostgreSQL、InfluxDB 3 Core 和应用配置示例；密钥通过 `.env` 提供，仓库只提交 `.env.example`。
- 提供 Flyway 初始迁移、演示初始化数据、健康检查、README 启动步骤和 MQTT/REST 样例报文。
- 生产 Webhook、TLS 双向 MQTT、审计留存策略、归档和性能目标留到部署环境配置及后续加固。

## 待实现者决定

- 包名、具体 Maven/Node 目录布局和 DTO/实体拆分方式。
- InfluxDB 3 写入与查询的批处理大小、超时、重试和失败补偿策略；默认采用有界异步批量写入、失败明确记录并可重试，PostgreSQL 不伪装成时序数据的长期存储替代。
- MQTT 适配器的具体 Spring Integration/客户端实现，以及在禁用配置下的自动装配方式。
- 用户登录采用服务端会话 Cookie；默认启用 CSRF 保护并设置 HttpOnly/SameSite Cookie。

## 官方技术依据

- Spring Boot 4 系统要求：<https://docs.spring.io/spring-boot/system-requirements.html>
- InfluxDB 3 Java 客户端及打包注意事项：<https://github.com/InfluxCommunity/influxdb3-java>
- InfluxDB 3 Java 客户端说明：<https://docs.influxdata.com/influxdb3/core/reference/client-libraries/v3/java/>
- InfluxDB 3 Core 数据保留：<https://docs.influxdata.com/influxdb3/core/reference/internals/data-retention/>
- 工作区模拟器位置：`pdm_simulator_dashboard/pdm-simulator/main.py`（HTTP 控制台 + MQTT telemetry 发布）。
