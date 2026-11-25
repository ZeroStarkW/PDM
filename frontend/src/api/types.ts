export interface User { id: number; username: string; roles: string[] }
export interface Factory { id: number; factoryCode: string; factoryName: string }
export interface Device {
  id: number
  factoryId: number
  factoryCode: string
  deviceCode: string
  deviceName: string
  deviceTypeId: number
  deviceTypeCode: string
  deviceTypeName: string
  rpmNominal: number
  powerKw?: number
  isoClass: string
  status: number
}
export interface DeviceType {
  id: number
  typeCode: string
  typeName: string
  deviceCount: number
  system: boolean
}
export type DiagnosticMetric =
  | 'FREQ_1X_AMP' | 'FREQ_1X_RATIO' | 'FREQ_2X_AMP' | 'FREQ_1X_OVER_2X_RATIO' | 'FREQ_2X_OVER_1X_RATIO'
  | 'V_RMS' | 'A_PEAK' | 'A_KURTOSIS' | 'ENVELOPE_RMS' | 'TEMPERATURE'
  | 'ORDER_PEAK_AMPLITUDE' | 'ORDER_PEAK_SNR'
  | 'MCSA_SIDEBAND_GAP_DB' | 'MCSA_SLIP_PCT'
  | 'TRIP_DURATION_RATIO' | 'COIL_CURRENT_RATIO' | 'CLOSING_VELOCITY_RATIO' | 'CONTACT_WEAR_RATIO'
  | 'VALVE_DEADBAND_RATIO' | 'VALVE_LEAK_RMS' | 'VALVE_TRAVEL_TIME_RATIO' | 'VALVE_FRICTION_RATIO'
export type DiagnosticOperator = 'GT' | 'GTE' | 'LT' | 'LTE'
export interface DiagnosticRuleInput {
  metric: DiagnosticMetric
  frequencyOrder?: number | null
  operator: DiagnosticOperator
  threshold: number
  priority: number
  factor: string
  evidence: string
  advice: string
  enabled: boolean
}
export interface DiagnosticRule extends DiagnosticRuleInput {
  id: number
  deviceTypeId: number
  version: number
}
/** 测点 / 通道的多模态扩展字段（v2 契约 B）：资产树测点节点与测点列表共用。 */
export interface ModalityChannelFields {
  /** 测点模态未登记机理插件时为 null（此时资产树不再整体 400，前端按“—”降级展示）。 */
  pluginIdentifier: string | null
  samplingRate: number | null
  engineeringUnit: string | null
  metadataConfig: Record<string, unknown> | null
  cadence: 'CONTINUOUS' | 'EVENT' | null
}
/** 新建测点可选通道字段（POST /api/v1/points）。 */
export interface PointChannelInput {
  samplingRate?: number
  engineeringUnit?: string
  metadataConfig?: Record<string, unknown>
  pluginIdentifier?: string
}
/** 更新测点可选通道字段（PUT /api/v1/points/{id}）：省略或 null 表示保持不变。 */
export type PointChannelUpdate = { [K in keyof PointChannelInput]?: PointChannelInput[K] | null }
export interface Point extends ModalityChannelFields { id: number; deviceId: number; pointCode: string; pointName: string; sensorMac?: string; modalityType?: string; freq1xBase?: number; vBaseRms?: number; baselineStatus: number; learnedSamples: number; healthIndex?: number; lastStatus?: string; lastTelemetryAt?: string; bearingModelId?: number | null; bearingModelName?: string | null; bpfoFactor?: number | null; bpfiFactor?: number | null; bsfFactor?: number | null; ftfFactor?: number | null }

/** 资产树（PHM 升级）：FACTORY → MACHINE → POINT 层级拓扑。 */
export interface AssetFactoryNode { id: number; code: string; name: string; nodeType: 'FACTORY'; children: AssetMachineNode[] }
export interface AssetMachineNode { id: number; code: string; name: string; nodeType: 'MACHINE'; rpmNominal: number; deviceType?: string | null; healthIndex?: number | null; status: string; children: AssetPointNode[] }
export interface AssetPointNode extends ModalityChannelFields { id: number; code: string; name: string; nodeType: 'POINT'; modalityType?: string | null; baselineStatus: string; learnedSamples: number; healthIndex?: number | null; lastStatus?: string | null; lastTelemetryAt?: string | null; bearingModel?: string | null }

export interface BearingModel {
  id: number
  modelName: string
  manufacturer: string
  ballCount: number
  ballDiameter: number
  pitchDiameter: number
  contactAngleDeg: number
  bpfoFactor: number
  bpfiFactor: number
  bsfFactor: number
  ftfFactor: number
}

/** 波形快照与谱帧（PHM 升级 §2.2/§7.2）。 */
export interface WaveformView {
  id: number
  pointId: number
  triggerType: 'THRESHOLD_EXCEEDED' | 'SCHEDULED' | 'ON_DEMAND'
  sampledAt: string
  samplingRate: number
  pointsCount: number
  unit: string
  storageType: string
  storageRef?: string | null
  healthIndex?: number | null
  waveform: number[]
  fftFreqs: number[]
  fftAmps: number[]
  envelopeFreqs: number[]
  envelopeAmps: number[]
}
// 频谱基线参考带（1/6 倍频程逐带 P50±3σ·MAD，冻结后不再随帧变化；学习期为空带）
export interface SpectrumBandView { centerHz: number; lowHz: number; highHz: number; p50: number; lower: number; upper: number }
export interface SpectrumKindBaseline {
  kind: 'FFT' | 'ENVELOPE'
  status: 'LEARNING' | 'ACTIVE' | string
  sampleCount: number
  lockedAt: string | null
  gridBands: number
  bands: SpectrumBandView[]
}
export interface SpectrumBaseline { pointId: number; minimumSamples: number; kinds: SpectrumKindBaseline[] }
export interface HarmonicCursor { order: number; hz: number }
export interface FaultLine { name: string; hz: number; amp: number; snr: number }
/** SSE SPECTRUM_FRAME 载荷（文档 §7.2 snake_case 命名）。 */
export interface SpectrumFrame {
  type: 'SPECTRUM_FRAME'
  frame_id: number
  timestamp: number
  sampling_rate: number
  unit: string
  point_id: number
  point_code: string
  rpm: number
  time_waveform: number[]
  fft_freqs: number[]
  fft_amps: number[]
  envelope_freqs: number[]
  envelope_amps: number[]
  harmonics: HarmonicCursor[]
  fault_lines: FaultLine[]
  bearing_model?: string | null
}
export type TelemetryFreshness = 'NO_DATA' | 'ONLINE' | 'STALE'
export interface PointHealth {
  pointId: number
  deviceId: number
  factoryCode: string
  deviceCode: string
  deviceName: string
  pointCode: string
  pointName: string
  modalityType?: string
  healthIndex?: number | null
  status: string
  baselineStatus: number
  freq1xBase?: number | null
  vBaseRms?: number | null
  learnedSamples: number
  lastTelemetryAt?: string | null
  qualityCode?: number | null
  sampledAt?: string | null
  ingestedAt?: string | null
  freshness?: TelemetryFreshness
}
export interface PointEvidence {
  pointId: number
  pointCode: string
  pointName: string
  healthIndex?: number | null
  status: string
  freshness: TelemetryFreshness
  qualityCode?: number | null
  lastTelemetryAt?: string | null
  scored: boolean
}
export interface DeviceHealth {
  deviceId: number
  factoryCode: string
  deviceCode: string
  deviceName: string
  deviceTypeName: string
  healthIndex?: number | null
  status: string
  scoringMethod: 'WORST_VALID_POINT' | string
  coverage: 'COMPLETE' | 'PARTIAL' | 'NONE'
  scoredPointCount: number
  pointCount: number
  onlinePointCount: number
  stalePointCount: number
  sensorCheckPointCount: number
  stoppedPointCount: number
  learningPointCount: number
  worstPointId?: number | null
  worstPointCode?: string | null
  points: PointEvidence[]
}
export interface FailurePrediction {
  deviceId: number
  status: 'INSUFFICIENT_DATA' | 'NO_DECLINE' | 'THRESHOLD_REACHED' | 'ESTIMATE_AVAILABLE'
  sourcePointId: number | null
  sourcePointCode: string | null
  sourcePointName: string | null
  currentHi: number | null
  slopePerDay: number | null
  projectedDays: number | null
  observationCount: number
  windowStart: string | null
  windowEnd: string | null
  fitScore: number | null
  confidence: 'LOW' | 'MEDIUM' | 'HIGH'
}
export interface Overview { factoryCount: number; deviceCount: number; pointCount: number; healthyCount: number; attentionCount: number; warningCount: number; dangerCount: number; unscoredDeviceCount: number; sensorCheckCount: number; stoppedCount: number; staleCount: number; learningCount: number; activeAlertCount: number; openWorkOrderCount: number; devices: DeviceHealth[]; points: PointHealth[] }
export interface Telemetry {
  timestamp: string
  factoryCode: string
  deviceCode: string
  pointCode: string
  vRms: number
  /** contract/2.x 上报路径不再强制 a_peak：未上报时为 null（展示需按 “—” 兜底）。 */
  aPeak?: number | null
  aKurtosis: number
  freq1xAmp: number
  freq2xAmp: number
  envelopeRms?: number | null
  envelopeBpfoAmp?: number | null
  rpm?: number | null
  temperature?: number | null
  healthIndex?: number | null
  dataQuality: number
  stopped: boolean
  status: string
}
export interface NotificationDelivery {
  id: number
  alertId: number
  eventKind: 'ALERT' | 'ESCALATION'
  state: 'PENDING' | 'DELIVERED' | 'FAILED'
  attempts: number
  nextAttemptAt: string | null
  lastError: string | null
  createdAt: string
  sentAt: string | null
}
export interface FailedNotificationPage {
  items: NotificationDelivery[]
  page: number
  totalPages: number
  totalElements: number
}
export interface Alert {
  id: number
  pointId: number
  deviceId: number
  factoryCode: string
  deviceCode: string
  deviceName: string
  pointCode: string
  pointName: string
  level: 'WARNING' | 'DANGER'
  healthIndex: number
  dominantFactor: string
  evidencePoints: PointEvidence[]
  deviceTypeId?: number | null
  deviceTypeName?: string | null
  diagnosticEvidence?: string | null
  diagnosticAdvice?: string | null
  diagnosticRuleId?: number | null
  diagnosticRuleVersion?: number | null
  diagnosticMetric?: DiagnosticMetric | null
  diagnosticOperator?: DiagnosticOperator | null
  diagnosticThreshold?: number | null
  diagnosticObservedValue?: number | null
  diagnosticFrequencyOrder?: number | null
  diagnosticObservedFrequencyHz?: number | null
  diagnosticTargetFrequencyHz?: number | null
  triggerAmplitude: number
  baselineAmplitude: number
  status: number
  notified: boolean
  notifications: NotificationDelivery[]
  occurrenceCount: number
  createdAt: string
  lastSeenAt: string
  defectDetail?: DefectDetail | null
  rawSnapshotUrl?: string | null
  /** 触发测点的模态与触发特征（triggerAmplitude / baselineAmplitude 所对应的特征编码）；旧版后端不返回，需按测点模态兜底。 */
  modalityType?: string | null
  triggerFeatureCode?: string | null
}
/** 确定性根因明细（PHM 升级）：主因 + dk/dt + η_dyn + 各特征贡献率。 */
export interface DefectContribution {
  feature: string
  current?: number | null
  baseline?: number | null
  ratio?: number | null
  score: number
}
export interface DefectDetail {
  primary_defect?: string
  device_health_index?: number
  point_health_index?: number
  slope_dk_dt_per_day?: number
  eta_dyn?: number
  contributions?: DefectContribution[]
}
export type DiagnosisVerdict = 'CONFIRMED' | 'DIFFERENT' | 'UNVERIFIED'
export interface WorkOrder {
  id: number
  /** 手工建单为 null（source === 'MANUAL' 时不再强制关联告警）。 */
  alertId: number | null
  /** 整机点检 / 检修工单可不指定测点，为 null。 */
  pointId: number | null
  deviceId: number
  factoryCode: string
  deviceCode: string
  deviceName: string
  pointCode: string | null
  alertLevel: string | null
  /** 无关联告警且测点无健康数据时为 null，前端必须判空后再 toFixed。 */
  healthIndex: number | null
  dominantFactor: string
  evidencePoints: PointEvidence[]
  status: string
  assignee?: string | null
  resolutionNote?: string | null
  diagnosisVerdict?: DiagnosisVerdict | null
  actualFinding?: string | null
  actionTaken?: string | null
  priority?: 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT' | null
  maintenanceAction?: string | null
  spareParts?: SparePart[] | null
  createdAt: string
  closedAt?: string | null
  /** ALERT=告警转单；MANUAL=点检手工建单。 */
  source?: 'ALERT' | 'MANUAL'
  title?: string | null
}
export interface SparePart { name: string; specification?: string | null; quantity?: number | null }
export interface WorkOrderClosure {
  resolutionNote: string
  diagnosisVerdict?: DiagnosisVerdict
  actualFinding?: string
  actionTaken?: string
}
export type WorkOrderPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'
/** POST /api/v1/work-orders/manual 请求体（pointId 省略即整机点检 / 检修）。 */
export interface ManualWorkOrderInput {
  deviceId: number
  pointId?: number | null
  title: string
  priority?: WorkOrderPriority | null
  maintenanceAction?: string | null
  assignee?: string | null
  spareParts?: SparePart[] | null
}
/** GET /api/v1/work-orders/page 查询参数（省略 / ALL / 空串均表示不过滤）。 */
export interface WorkOrderQueryParams {
  page?: number
  size?: number
  factoryCode?: string | null
  deviceId?: number | null
  status?: 'ALL' | 'PENDING' | 'OPEN' | 'IN_PROGRESS' | 'CLOSED' | 'SUPPRESSED' | string
  priority?: 'ALL' | WorkOrderPriority | string
  source?: 'ALL' | 'ALERT' | 'MANUAL' | string
  keyword?: string
}
export interface WorkOrderPage {
  items: WorkOrder[]
  page: number
  size: number
  totalPages: number
  totalItems: number
  /** 当前筛选范围（不含状态裁剪）下的待办（OPEN + IN_PROGRESS）总数。 */
  pendingCount: number
}
/** GET /api/v1/alerts/page 查询参数（省略 / ALL / 空串均表示不过滤）。 */
export interface AlertQueryParams {
  page?: number
  size?: number
  factoryCode?: string | null
  deviceId?: number | null
  level?: 'ALL' | 'DANGER' | 'WARNING' | string
  status?: 'ALL' | 'OPEN' | 'UNACKNOWLEDGED' | 'ACKNOWLEDGED' | 'CLOSED' | 'SUPPRESSED' | 'RESOLVED' | string
  keyword?: string
}
export interface AlertPage {
  items: Alert[]
  page: number
  size: number
  totalPages: number
  totalItems: number
  dangerOpenCount: number
  warningOpenCount: number
  resolvedCount: number
}
/** 多工况分箱汇总（V21）：一个出现过的工况箱一行；binIndex 0 = 额定 / 默认稳态工况，非 0 = 转速段 / 负载段独立学习箱。 */
export interface RegimeBinSummary {
  binIndex: number
  regimeLabel: string
  status: string
  sampleCount: number
  lockedAt: string | null
  featureP50: Record<string, number>
}
export interface Baseline { pointId: number; status: string; learnedSamples: number; minimumSamples: number; learningDurationSeconds: number; freq1xBase?: number; freq2xBase?: number; envelopeBpfoBase?: number; vBaseRms?: number; tempBase?: number; cv1x?: number; lockedAt?: string; warning?: string; regimes?: RegimeBinSummary[] }
export interface DeviceBaselineOperation { deviceId: number; deviceCode: string; action: 'RESET_REQUESTED' | 'RESET_CONFIRMED' | 'NO_ACTIVE_BASELINE'; affectedCount: number; baselines: Baseline[] }

// ==== 多模态通道（v2 契约；{code} 路径变量为测点 id） ====
/** 已注册机理插件目录（GET /api/v1/modalities）。 */
export interface ModalityCatalog {
  modalityType: string
  pluginIdentifier: string
  cadence: 'CONTINUOUS' | 'EVENT'
  requiredFeatures: string[]
  baselineFeatureCodes: string[]
  seriesFeatureCodes: string[]
  lowerIsWorseFeatures: string[]
  /** 告警触发特征（当前值/基线值即告警的触发/基线幅值）。 */
  primaryFeatureCode: string | null
  /** 趋势图默认特征；旧版后端不返回，前端按模态静态兜底。 */
  trendFeatureCode?: string | null
  metadataFields: ModalityMetadataField[]
  alertsEnabled: boolean
  staleAfterSeconds: number
  baselineLearning: ModalityBaselineLearning
}
export interface ModalityMetadataField { key: string; label: string; description: string; defaultValue: number | null }
export interface ModalityBaselineLearning { windowSeconds: number | null; pooled: boolean }

/** 通道多模基线矩阵（GET /api/v2/channels/{code}/baseline）。 */
export interface ChannelFeatureState { featureKey: string; baselineP50: number | null; cvValue: number | null; sampleCount: number; status: 'ACTIVE' | 'LEARNING' | 'DIVERGED' | 'PENDING_RESET' | 'PROVISIONAL' | string; lockedAt: string | null; /** 多工况分箱（旧版后端不返回，默认按 0 号额定/默认箱展示）。 */ binIndex?: number; regimeLabel?: string }
export interface ChannelBaseline {
  channelCode: string; pointId: number; deviceId: number; deviceCode: string; factoryCode: string
  modalityType: string; pluginIdentifier: string; cadence: 'CONTINUOUS' | 'EVENT'
  status: 'ACTIVE' | 'LEARNING' | 'DIVERGED' | string
  learnedSamples: number; minimumSamples: number; lockedAt: string | null; warning: string | null
  learningWindowSeconds: number | null; pooled: boolean
  featureStates: ChannelFeatureState[]
}

/** 跨模态时序（GET /api/v2/telemetry/{code}/series）：行按时间升序；metrics 仅含该行出现的特征。 */
export interface ChannelSeriesRow { timestamp: number; health_index: number | null; status: string; data_quality: number; metrics: Record<string, number> }
export interface ChannelSeries { channelCode: string; pointId: number; modalityType: string; step: string; features: string[]; rows: ChannelSeriesRow[] }

/** 最新突发曲线快照（GET /api/v2/snapshots/{code}/latest；无快照时 404）。 */
export interface StrokeAnnotations { tripStartMs: number | null; contactSeparationMs: number | null; brakingMs: number | null; totalTimeMs: number | null; peakCurrentA: number | null; fullStrokeMm: number | null; maxVelocityMps: number | null }
export interface ValveLoopAnalysis { deadbandEstimatePct: number | null; loopAreaPct2: number | null; hysteresisPct: number | null; maxDeviationPct: number | null; reversalCount: number }
export interface ChannelSnapshot {
  id: number; pointId: number; channelCode: string; deviceCode: string; modalityType: string
  triggerType: string; timestamp: number; sampledAt: string
  samplingRate: number | null; pointsCount: number | null; engineeringUnit: string | null
  storageBackend: string | null; snapshotUrl: string | null; storageRef: string | null
  healthIndex: number | null
  previewCurve: number[]
  curves: Record<string, number[]>
  curveUnits: Record<string, string>
  metrics: Record<string, number>
  annotations: Record<string, unknown>
}

/** 调节阀 CV/FB 迟滞回环（GET /api/v2/channels/{code}/hysteresis?window=PT1H）。 */
export interface ValveLoopPoint { timestamp: number; cv: number; fb: number }
export interface ValveLoop {
  channelCode: string; modalityType: string; window: string; sampleCount: number; from: number | null; to: number | null
  status: 'OK' | 'NO_DATA' | 'UNSUPPORTED_MODALITY' | string
  analysis: ValveLoopAnalysis
  points: ValveLoopPoint[]
  /** 原始样本达到上限被截断；limit 为原始样本上限。旧版后端不返回这两个字段。 */
  truncated?: boolean
  limit?: number
}

/** 多模态实时帧（SSE /ws/v2/stream，snake_case，设计文档 §6.2）。未评分样本（基线学习/工况不可评估）health_index 与 alarm_level 为 null。 */
export interface ModalityStreamFrameBase {
  timestamp: number; modality_type: string; channel_code: string; metrics: Record<string, number>; health_index: number | null; alarm_level: string | null
  /** 样本状态（如 BASELINE_LEARNING / CONDITION_REJECTED / ANALYSIS_BUFFERING）；旧版后端不返回。 */
  status?: string | null
}
export type StrokeEventFrame = ModalityStreamFrameBase & { frame_type: 'STROKE_EVENT_FRAME'; curves: Record<string, number[]>; curve_units: Record<string, string>; annotations: Record<string, unknown> }
export type ValveLoopFrame = ModalityStreamFrameBase & { frame_type: 'VALVE_LOOP_FRAME'; curves: Record<string, number[]>; curve_units: Record<string, string>; annotations: Record<string, unknown> }
export type MetricsFrame = ModalityStreamFrameBase & { frame_type: 'METRICS_FRAME' }
export type ModalityStreamFrame = MetricsFrame | StrokeEventFrame | ValveLoopFrame

export type MaintenanceDueState = 'UPCOMING' | 'DUE' | 'OVERDUE' | 'INACTIVE'
export type MaintenanceTaskStatus = 'OPEN' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'
export type MaintenanceTaskFilter = 'ALL' | 'ACTIVE' | 'HISTORY'

export interface MaintenancePage<T> {
  items: T[]
  page: number
  totalPages: number
  totalItems: number
}

export interface MaintenancePlanInput {
  deviceId: number
  title: string
  intervalDays: number
  nextDueAt: string
  instructions: string | null
  assignee: string | null
  enabled: boolean
}

export interface MaintenancePlan extends MaintenancePlanInput {
  id: number
  deviceCode: string
  deviceName: string
  version: number
  dueState: MaintenanceDueState
}

export interface MaintenanceTask {
  id: number
  planId: number | null
  planTitle: string | null
  instructions: string | null
  deviceId: number
  deviceCode: string
  deviceName: string
  dueAt: string
  dueState: MaintenanceDueState
  status: MaintenanceTaskStatus
  assignee: string | null
  findings: string | null
  action: string | null
  createdAt: string
  updatedAt: string
  completedAt: string | null
}
