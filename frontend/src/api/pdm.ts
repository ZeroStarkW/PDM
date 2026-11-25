import { api } from './http'
import type { FailedNotificationPage, NotificationDelivery } from './types'
import type { MaintenancePage, MaintenancePlan, MaintenancePlanInput, MaintenanceTask, MaintenanceTaskFilter } from './types'
import type { Alert, AlertPage, AlertQueryParams, AssetFactoryNode, Baseline, BearingModel, ChannelBaseline, ChannelSeries, ChannelSnapshot, Device, DeviceBaselineOperation, DeviceType, DiagnosticRule, DiagnosticRuleInput, Factory, FailurePrediction, ManualWorkOrderInput, ModalityCatalog, ModalityStreamFrame, Overview, Point, PointChannelInput, PointChannelUpdate, SpectrumBaseline, SpectrumFrame, Telemetry, ValveLoop, WaveformView, WorkOrder, WorkOrderClosure, WorkOrderPage, WorkOrderQueryParams, SparePart } from './types'

/** 拼查询串：undefined / null / 空串 / 'ALL' 一律视为不过滤，省略该参数。 */
function query(params: Record<string, unknown>): string {
  const usp = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    if (typeof value === 'string' && value === 'ALL') continue
    usp.set(key, String(value))
  }
  const text = usp.toString()
  return text ? `?${text}` : ''
}

export const getOverview = (signal?: AbortSignal) => api<Overview>('/dashboard/overview', { signal })
export const getFailurePrediction = (deviceId: number, signal?: AbortSignal) =>
  api<FailurePrediction>(`/devices/${deviceId}/failure-prediction`, { signal })
export const getFactories = () => api<Factory[]>('/factories')
export const createFactory = (data: { factoryCode: string; factoryName: string }) => api<Factory>('/factories', { method: 'POST', body: JSON.stringify(data) })
export const updateFactory = (id: number, data: { factoryName: string }) => api<Factory>(`/factories/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const getDevices = (factoryCode: string) => api<Device[]>(`/devices?factoryCode=${encodeURIComponent(factoryCode)}`)
export const createDevice = (data: { factoryCode: string; deviceCode: string; deviceName: string; rpmNominal?: number | null; powerKw?: number; deviceTypeId?: number; isoClass?: string }) => api<Device>('/devices', { method: 'POST', body: JSON.stringify(data) })
export const updateDevice = (id: number, data: { deviceName: string; rpmNominal?: number | null; powerKw?: number; status: number; deviceTypeId?: number; isoClass?: string }) => api<Device>(`/devices/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const getDeviceTypes = () => api<DeviceType[]>('/device-types')
export const createDeviceType = (data: { typeCode: string; typeName: string }) => api<DeviceType>('/device-types', { method: 'POST', body: JSON.stringify(data) })
export const updateDeviceType = (id: number, data: { typeName: string }) => api<DeviceType>(`/device-types/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const deleteDeviceType = (id: number) => api<void>(`/device-types/${id}`, { method: 'DELETE' })
export const getDiagnosticRules = (typeId: number, signal?: AbortSignal) => api<DiagnosticRule[]>(`/device-types/${typeId}/rules`, { signal })
export const createDiagnosticRule = (typeId: number, data: DiagnosticRuleInput) => api<DiagnosticRule>(`/device-types/${typeId}/rules`, { method: 'POST', body: JSON.stringify(data) })
export const updateDiagnosticRule = (typeId: number, ruleId: number, data: DiagnosticRuleInput, expectedVersion: number) =>
  api<DiagnosticRule>(`/device-types/${typeId}/rules/${ruleId}`, { method: 'PUT', body: JSON.stringify({ ...data, expectedVersion }) })
export const getPoints = (deviceId: number) => api<Point[]>(`/points?deviceId=${deviceId}`)
/** 按车间一次取回全部测点：资产管理页批量装配，替代按设备逐台 getPoints 的 N+1 请求。 */
export const getPointsByFactory = (factoryCode: string) => api<Point[]>(`/points?factoryCode=${encodeURIComponent(factoryCode)}`)
export const createPoint = (data: { deviceId: number; pointCode: string; pointName: string; sensorMac?: string; modalityType?: string; bearingModelId?: number | null } & PointChannelInput) => api<Point>('/points', { method: 'POST', body: JSON.stringify(data) })
export const updatePoint = (id: number, data: { pointName: string; sensorMac?: string; bearingModelId?: number | null } & PointChannelUpdate) => api<Point>(`/points/${id}`, { method: 'PUT', body: JSON.stringify(data) })
export const getTelemetry = (pointId: number, from: string, to: string, interval: string, signal?: AbortSignal) => api<Telemetry[]>(
  `/points/${pointId}/telemetry?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&interval=${encodeURIComponent(interval)}`,
  { signal },
)
export const getAlerts = (signal?: AbortSignal) => api<Alert[]>('/alerts', { signal })
/** 服务端分页 + 多维筛选（车间/级别/状态/关键字）；原 getAlerts 保留供 WorkbenchView 等存量调用方使用。 */
export const getAlertsPage = (params: AlertQueryParams = {}, signal?: AbortSignal) =>
  api<AlertPage>(`/alerts/page${query({ ...params })}`, { signal })
export const getFailedNotifications = (page: number, size: number, signal?: AbortSignal) =>
  api<FailedNotificationPage>(`/notifications/failed?page=${page}&size=${size}`, { signal })
export const retryNotification = (id: number) => api<NotificationDelivery>(`/notifications/${id}/retry`, { method: 'POST' })
export const acknowledgeAlert = (id: number) => api<Alert>(`/alerts/${id}/acknowledge`, { method: 'POST' })
export const suppressAlert = (id: number, note: string) => api<Alert>(`/alerts/${id}/suppress`, { method: 'POST', body: JSON.stringify({ note }) })
export const getWorkOrders = (signal?: AbortSignal) => api<WorkOrder[]>('/work-orders', { signal })
/** 服务端分页 + 多维筛选（车间/设备/状态/优先级/来源/关键字）；原 getWorkOrders 保留供 WorkbenchView 使用。 */
export const getWorkOrdersPage = (params: WorkOrderQueryParams = {}, signal?: AbortSignal) =>
  api<WorkOrderPage>(`/work-orders/page${query({ ...params })}`, { signal })
/** 手工建单（ADMIN/OPERATOR）：日常点检发现跑冒滴漏 / 异响时直接为设备创建独立维修工单。 */
export const createManualWorkOrder = (data: ManualWorkOrderInput) =>
  api<WorkOrder>('/work-orders/manual', { method: 'POST', body: JSON.stringify(data) })
export const assignWorkOrder = (id: number, assignee: string) => api<WorkOrder>(`/work-orders/${id}/assign`, { method: 'POST', body: JSON.stringify({ assignee }) })
export const closeWorkOrder = (id: number, data: WorkOrderClosure & { requestBaselineReset?: boolean }) => api<WorkOrder>(`/work-orders/${id}/close`, { method: 'POST', body: JSON.stringify(data) })
export const getMaintenancePlans = (page = 0) => api<MaintenancePage<MaintenancePlan>>(`/maintenance/plans?page=${page}`)
export const getMaintenancePlan = (id: number) => api<MaintenancePlan>(`/maintenance/plans/${id}`)
export const createMaintenancePlan = (data: MaintenancePlanInput) =>
  api<MaintenancePlan>('/maintenance/plans', { method: 'POST', body: JSON.stringify(data) })
export const updateMaintenancePlan = (id: number, data: MaintenancePlanInput, expectedVersion: number) =>
  api<MaintenancePlan>(`/maintenance/plans/${id}`, { method: 'PUT', body: JSON.stringify({ ...data, expectedVersion }) })
export const getMaintenanceTasks = (filter: MaintenanceTaskFilter = 'ALL', page = 0) =>
  api<MaintenancePage<MaintenanceTask>>(`/maintenance/tasks?filter=${filter}&page=${page}`)
export const assignMaintenanceTask = (id: number, assignee: string) =>
  api<MaintenanceTask>(`/maintenance/tasks/${id}/assign`, { method: 'POST', body: JSON.stringify({ assignee }) })
export const startMaintenanceTask = (id: number) =>
  api<MaintenanceTask>(`/maintenance/tasks/${id}/start`, { method: 'POST' })
export const completeMaintenanceTask = (id: number, findings: string, action: string) =>
  api<MaintenanceTask>(`/maintenance/tasks/${id}/complete`, { method: 'POST', body: JSON.stringify({ findings, action }) })
export const cancelMaintenanceTask = (id: number) =>
  api<MaintenanceTask>(`/maintenance/tasks/${id}/cancel`, { method: 'POST' })
export const getBaseline = (pointId: number) => api<Baseline>(`/points/${pointId}/baseline`)
export const requestDeviceBaselineReset = (deviceId: number, pointIds?: number[]) => api<DeviceBaselineOperation>(`/devices/${deviceId}/baseline/reset-request`, { method: 'POST', body: JSON.stringify({ pointIds }) })
export const confirmDeviceBaselineReset = (deviceId: number, pointIds?: number[]) => api<DeviceBaselineOperation>(`/devices/${deviceId}/baseline/confirm-reset`, { method: 'POST', body: JSON.stringify({ pointIds }) })

// ==== PHM 升级新增接口（文档 §7/§8） ====
export const getAssetTree = (signal?: AbortSignal) => api<AssetFactoryNode[]>('/assets/tree', { signal })
export const getBearingModels = (keyword?: string) =>
  api<BearingModel[]>(`/bearings/models${keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''}`)
export const getWaveform = (pointId: number, signal?: AbortSignal) =>
  api<WaveformView>(`/diagnostics/${pointId}/waveform`, { signal })
/** 频谱基线参考带（冻结后为稳定对照；学习期 kinds 内 bands 为空，按 sampleCount/minimumSamples 展示进度）。 */
export const getSpectrumBaseline = (pointId: number, signal?: AbortSignal) =>
  api<SpectrumBaseline>(`/diagnostics/${pointId}/spectrum-baseline`, { signal })
export const captureWaveform = (pointId: number) =>
  api<{ dispatched: boolean; message?: string }>(`/diagnostics/${pointId}/capture`, { method: 'POST' })
export const createWorkOrder = (data: { alarmEventId: number; action?: string; priority?: string; spareParts?: SparePart[] }) =>
  api<WorkOrder>('/workorders', { method: 'POST', body: JSON.stringify(data) })

/** SSE 订阅回调：onFrame 收具名事件载荷（坏帧已在订阅层忽略）；onOpen 在连接建立时触发（用于区分“已连接等待上报”与 LIVE）。 */
export interface StreamHandlers<Frame> { onFrame: (frame: Frame) => void; onError?: (err: Event) => void; onOpen?: () => void }

/** 订阅实时频谱示波器 SSE（同源会话 Cookie 随 EventSource 自动携带）。 */
export function openLiveStream(pointId: number, onFrame: (frame: SpectrumFrame) => void, onError?: (err: Event) => void, onOpen?: () => void): EventSource {
  const source = new EventSource(`/ws/v1/live-stream?pointId=${pointId}`)
  source.addEventListener('spectrum_frame', (event) => {
    try { onFrame(JSON.parse((event as MessageEvent).data) as SpectrumFrame) } catch { /* 忽略坏帧 */ }
  })
  if (onError) source.onerror = (event) => onError(event)
  if (onOpen) source.onopen = () => onOpen()
  return source
}

// ==== 多模态通道 v2 接口（设计文档 §5/§6；路径以 /api 开头，api() 不再叠加 /api/v1；{code} 为测点 id） ====
export const getModalities = (signal?: AbortSignal) => api<ModalityCatalog[]>('/api/v1/modalities', { signal })

let catalogRequest: Promise<ModalityCatalog[]> | undefined
/**
 * 模态目录：整个会话只请求一次并在各视图（总览 / 测点详情 / 工作站）间共享，避免同一目录反复拉取。
 * 失败时返回空数组（调用方按通道自带字段与静态兜底降级展示），且不缓存失败，下次进入视图会重试。
 */
export function loadModalityCatalog(): Promise<ModalityCatalog[]> {
  return (catalogRequest ??= getModalities().catch(() => { catalogRequest = undefined; return [] as ModalityCatalog[] }))
}
export const getChannelBaseline = (code: number, signal?: AbortSignal) => api<ChannelBaseline>(`/api/v2/channels/${code}/baseline`, { signal })
export const getChannelSeries = (code: number, startTs: number, endTs: number, step = '5m', signal?: AbortSignal) =>
  api<ChannelSeries>(`/api/v2/telemetry/${code}/series?start_ts=${startTs}&end_ts=${endTs}&step=${encodeURIComponent(step)}`, { signal })
export const getLatestSnapshot = (code: number, signal?: AbortSignal) => api<ChannelSnapshot>(`/api/v2/snapshots/${code}/latest`, { signal })
export const getValveLoop = (code: number, timeWindow = 'PT1H', signal?: AbortSignal) =>
  api<ValveLoop>(`/api/v2/channels/${code}/hysteresis?window=${encodeURIComponent(timeWindow)}`, { signal })

/** 订阅多模态通道实时 SSE（同源会话 Cookie 随 EventSource 自动携带）；监听三类命名事件，坏帧忽略。 */
export function openModalityStream(pointId: number, handlers: StreamHandlers<ModalityStreamFrame>): EventSource {
  const source = new EventSource(`/ws/v2/stream?channelCode=${pointId}`)
  const listener = (event: Event) => {
    try {
      const frame = JSON.parse((event as MessageEvent).data) as ModalityStreamFrame
      if (frame && typeof frame.frame_type === 'string') handlers.onFrame(frame)
    } catch { /* 忽略坏帧 */ }
  }
  for (const name of ['metrics_frame', 'stroke_event_frame', 'valve_loop_frame']) source.addEventListener(name, listener)
  if (handlers.onError) source.onerror = (event) => handlers.onError?.(event)
  const { onOpen } = handlers
  if (onOpen) source.onopen = () => onOpen()
  return source
}
