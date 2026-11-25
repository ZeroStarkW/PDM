import type { ChannelBaseline, ChannelSeriesRow, DiagnosticMetric, ModalityCatalog, ModalityStreamFrameBase } from '@/api/types'
import { diagnosticMetrics } from '@/diagnostics'
import { pointStatusLabel } from '@/telemetry-status'

/** 多模态显示名（模态标识 → 中文）；未登记的模态原样显示标识。 */
export const modalityNames: Record<string, string> = {
  ROTATING_VIB: '旋转振动',
  SWITCHGEAR_EVENT: '高压断路器',
  CONTROL_VALVE_PROCESS: '控制调节阀',
  MCSA: '电机电流 (MCSA)',
}
const modalityShortNames: Record<string, string> = {
  ROTATING_VIB: '振动',
  SWITCHGEAR_EVENT: '断路器',
  CONTROL_VALVE_PROCESS: '调节阀',
  MCSA: 'MCSA',
}
/** 模态标签色调：旋转振动 blue / 高压断路器 orange / 控制调节阀 green / 电机电流 gray。 */
const modalityTones: Record<string, string> = {
  ROTATING_VIB: 'blue',
  SWITCHGEAR_EVENT: 'orange',
  CONTROL_VALVE_PROCESS: 'green',
  MCSA: 'gray',
}
/** 资产树内按模态分组时的排序；未登记模态排在最后。 */
const modalityOrder = ['ROTATING_VIB', 'SWITCHGEAR_EVENT', 'CONTROL_VALVE_PROCESS', 'MCSA']

export function modalityName(type?: string | null) { return (type && modalityNames[type]) || type || '未知模态' }
export function modalityShortName(type?: string | null) { return (type && modalityShortNames[type]) || type || '未知' }
export function modalityTone(type?: string | null) { return (type && modalityTones[type]) || 'gray' }
export function modalityRank(type?: string | null) {
  const index = type ? modalityOrder.indexOf(type) : -1
  return index < 0 ? modalityOrder.length : index
}
export function isVibration(type?: string | null) { return type === 'ROTATING_VIB' }
export function cadenceLabel(cadence?: string | null) {
  return ({ CONTINUOUS: '连续采集', EVENT: '事件触发' } as Record<string, string>)[cadence ?? ''] ?? cadence ?? '—'
}

/** 特征编码 → 中文名称与工程单位；未登记的编码原样显示、单位留空。 */
export interface FeatureMeta { label: string; unit: string }
export const featureMeta: Record<string, FeatureMeta> = {
  // 高压断路器（SWITCHGEAR_EVENT）
  trip_duration_ms: { label: '分闸时间', unit: 'ms' },
  peak_coil_current_a: { label: '线圈电流峰值', unit: 'A' },
  closing_velocity_mps: { label: '合闸速度', unit: 'm/s' },
  contact_wear_ratio: { label: '触头磨损比', unit: '' },
  uhf_pd_count: { label: '特高频局放计数', unit: '次' },
  // 控制调节阀（CONTROL_VALVE_PROCESS）
  hysteresis_deadband_pct: { label: '回环死区', unit: '%' },
  travel_time_lag_ms: { label: '行程滞后', unit: 'ms' },
  ultrasonic_leak_rms: { label: '超声泄漏 RMS', unit: '' },
  friction_force_kn: { label: '摩擦力', unit: 'kN' },
  cv_pct: { label: '指令开度', unit: '%' },
  fb_pct: { label: '反馈开度', unit: '%' },
  // 电机电流（MCSA）
  mains_freq_hz: { label: '工频', unit: 'Hz' },
  slip_pct: { label: '转差率', unit: '%' },
  load_pct: { label: '负载率', unit: '%' },
  sideband_amp_db: { label: '边带幅值', unit: 'dB' },
  carrier_amp_db: { label: '载波幅值', unit: 'dB' },
  sideband_ratio: { label: '边频带/载波比', unit: '无量纲' },
  // 旋转振动（ROTATING_VIB，v2 通道特征码）
  v_rms: { label: '振动速度 RMS', unit: 'mm/s' },
  freq_1x_amp: { label: '1X 振幅', unit: 'mm/s' },
  freq_2x_amp: { label: '2X 振幅', unit: 'mm/s' },
  feat_3x_amp: { label: '3X 振幅', unit: 'mm/s' },
  feat_sub_amp: { label: '次同步振幅', unit: 'mm/s' },
  a_peak: { label: '加速度峰值', unit: 'm/s²' },
  a_kurtosis: { label: '加速度峭度', unit: '' },
  envelope_rms: { label: '包络 RMS', unit: '' },
  envelope_bpfo_amp: { label: '包络 BPFO 幅值', unit: '' },
  env_bpfi_amp: { label: '包络 BPFI 幅值', unit: '' },
  sei_index: { label: '冲击能量指数', unit: '' },
  crest_factor: { label: '峰值因子', unit: '' },
  rpm: { label: '转速', unit: 'RPM' },
  temperature: { label: '温度', unit: '°C' },
  temp_ambient: { label: '环境温度', unit: '°C' },
  temp_rise: { label: '温升', unit: '°C' },
}

export function featureInfo(code?: string | null): FeatureMeta {
  return (code && featureMeta[code]) || { label: code || '—', unit: '' }
}
export function featureTitle(code?: string | null) {
  const { label, unit } = featureInfo(code)
  return unit ? `${label} (${unit})` : label
}
/** 特征显示名：兼容 v2 通道特征码与 v1 诊断指标码（如 FREQ_1X_AMP）；未登记的原样显示。 */
export function featureLabel(code?: string | null) {
  if (!code) return '—'
  return featureMeta[code]?.label ?? diagnosticMetrics[code as DiagnosticMetric]?.label ?? code
}

/** 数值格式化：null / 非有限值显示为 —；小量值自动保留有效数字，避免出现 0.00。 */
export function formatNumber(value: number | null | undefined, digits = 2): string {
  if (value == null || !Number.isFinite(value)) return '—'
  const magnitude = value === 0 ? 0 : Math.floor(Math.log10(Math.abs(value)))
  const decimals = Math.min(10, Math.max(digits, digits - magnitude))
  return String(Number(value.toFixed(decimals)))
}
/**
 * 特征值带单位文本，按“有效数字”格式化（formatNumber 对 ≥10 的值给的是固定 3 位小数，不是有效数字）。
 * 告警触发幅值跨模态量级差异很大（断路器 ms、调节阀 %、MCSA 千分位比值），统一取 3 位有效数字才都读得清；
 * 缺值显示为 —，无单位（无量纲比值未登记单位时）不追加单位。
 */
export function formatFeatureValue(value: number | null | undefined, unit?: string | null, significantDigits = 3): string {
  if (value == null || !Number.isFinite(value)) return '—'
  const magnitude = value === 0 ? 0 : Math.floor(Math.log10(Math.abs(value)))
  const decimals = Math.max(0, significantDigits - 1 - magnitude)
  const text = String(Number(value.toFixed(decimals)))
  return unit ? `${text} ${unit}` : text
}

/** 趋势图默认特征的静态兜底（目录未登记 trendFeatureCode 时按模态取）。 */
const defaultTrendFeatures: Record<string, string> = {
  ROTATING_VIB: 'v_rms', MCSA: 'sideband_amp_db', CONTROL_VALVE_PROCESS: 'hysteresis_deadband_pct', SWITCHGEAR_EVENT: 'trip_duration_ms',
}
/** 过程量（指令/反馈开度）不作为健康趋势的默认特征。 */
const processOnlyFeatures = new Set(['cv_pct', 'fb_pct'])
type TrendCatalog = Partial<Pick<ModalityCatalog, 'trendFeatureCode' | 'primaryFeatureCode'>> | null | undefined

/**
 * 趋势图默认特征（时序特征为字典序，直接取首个会落到 a_kurtosis / cv_pct 之类）：
 * 目录 trendFeatureCode → 模态静态兜底 → 目录 primaryFeatureCode → 首个非过程量特征 → 首个特征。
 * 始终返回 features 的成员；features 为空时返回空串。
 */
export function defaultTrendFeature(modalityType?: string | null, catalog: TrendCatalog = null, features: readonly string[] = []): string {
  const members = (features ?? []).filter((code) => typeof code === 'string' && code !== '')
  const member = (code: string | null | undefined) => (code && members.includes(code) ? code : null)
  return member(catalog?.trendFeatureCode)
    ?? member(defaultTrendFeatures[modalityType ?? ''])
    ?? member(catalog?.primaryFeatureCode)
    ?? members.find((code) => !processOnlyFeatures.has(code))
    ?? members[0]
    ?? ''
}

/** 告警触发特征的静态兜底（后端未返回 triggerFeatureCode 时按测点模态推断）。 */
const alertTriggerFeatures: Record<string, string> = {
  ROTATING_VIB: 'freq_1x_amp', MCSA: 'sideband_ratio', SWITCHGEAR_EVENT: 'trip_duration_ms', CONTROL_VALVE_PROCESS: 'hysteresis_deadband_pct',
}
/** 告警触发/基线幅值所指的特征：triggerFeatureCode 优先，否则按模态兜底；模态未知时不臆测单位。 */
export function triggerFeature(featureCode?: string | null, modalityType?: string | null): FeatureMeta & { code: string | null } {
  const code = featureCode || (modalityType ? alertTriggerFeatures[modalityType] : null) || null
  return { code, ...(code ? featureInfo(code) : { label: '触发特征值', unit: '' } as FeatureMeta) }
}

/** 比值 k = 当前值 / 基线值；基线缺失或为零时返回 null。 */
export function ratioOf(current: number | null | undefined, baseline: number | null | undefined) {
  if (current == null || baseline == null || !Number.isFinite(current) || !Number.isFinite(baseline)) return null
  return Math.abs(baseline) < 1e-12 ? null : current / baseline
}
export function formatRatio(ratio: number | null | undefined) { return ratio == null ? '—' : `k=${formatNumber(ratio, 2)}` }

export function formatTime(value: number | string | null | undefined) {
  if (value == null || value === '') return '—'
  const time = new Date(value).getTime()
  return Number.isFinite(time) ? new Date(time).toLocaleString() : '—'
}

/** 相对时间（“x 分钟前”）；时钟回拨时按“刚刚”处理。 */
export function timeAgo(value: number | string | null | undefined, now = Date.now()) {
  const time = value == null || value === '' ? NaN : new Date(value).getTime()
  if (!Number.isFinite(time)) return '—'
  const seconds = Math.max(0, Math.round((now - time) / 1000))
  if (seconds < 60) return '刚刚'
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`
  return `${Math.floor(seconds / 86400)} 天前`
}

/** 时长（秒）→ 可读文本。 */
export function formatDuration(seconds: number | null | undefined) {
  if (seconds == null || !Number.isFinite(seconds) || seconds <= 0) return '—'
  if (seconds % 86400 === 0) return `${seconds / 86400} 天`
  if (seconds % 3600 === 0 || seconds >= 3600) return `${formatNumber(seconds / 3600, 1)} 小时`
  return `${formatNumber(seconds / 60, 1)} 分钟`
}

/** 新鲜度阈值（秒）：目录登记值优先；目录缺失时连续型沿用全局 stale-after（5 分钟），事件型 30 天。 */
export function staleAfterSeconds(cadence?: string | null, catalog?: { staleAfterSeconds?: number | null } | null): number | null {
  const declared = catalog?.staleAfterSeconds
  if (typeof declared === 'number' && declared > 0) return declared
  return cadence === 'EVENT' ? 30 * 86400 : cadence === 'CONTINUOUS' ? 300 : null
}
/** 已知的事件型（动作触发）模态；仅在目录缺失时用于兜底判断。 */
const eventModalities = new Set(['SWITCHGEAR_EVENT'])
/** 通道是否为事件型：目录 cadence 优先，目录缺失时按已知事件型模态兜底。 */
export function isEventChannel(modalityType?: string | null, catalog?: { cadence?: string | null } | null) {
  return catalog?.cadence ? catalog.cadence === 'EVENT' : !!modalityType && eventModalities.has(modalityType)
}
/** 新鲜度口径说明：事件型通道动作稀疏，“在线”按更长时限判定。 */
export function freshnessRule(cadence?: string | null, staleAfter?: number | null) {
  if (!staleAfter || staleAfter <= 0) return ''
  return cadence === 'EVENT'
    ? `事件型通道：${formatDuration(staleAfter)} 内有一次动作即视为在线`
    : `超过 ${formatDuration(staleAfter)} 未上报即视为数据过期`
}

export interface ReportFreshness { label: string; tone: string; stale: boolean; reportedAt: number | null }
/** “最近上报 … / 数据过期”标签：超过模态新鲜度阈值即标过期；阈值为空时只显示最近上报时间。 */
export function reportFreshness(reportedAt: number | string | null | undefined, staleAfter: number | null, options: { now?: number; event?: boolean } = {}): ReportFreshness {
  const now = options.now ?? Date.now()
  const time = reportedAt == null || reportedAt === '' ? NaN : new Date(reportedAt).getTime()
  const noun = options.event ? '动作' : '上报'
  if (!Number.isFinite(time)) return { label: `暂无${noun}`, tone: 'gray', stale: false, reportedAt: null }
  const stale = staleAfter != null && staleAfter > 0 && now - time > staleAfter * 1000
  return { label: `${stale ? '数据过期 · ' : ''}最近${noun} ${timeAgo(time, now)}`, tone: stale ? 'amber' : 'green', stale, reportedAt: time }
}

/** 健康分级（契约：≥70 正常 / ≥50 关注 / ≥30 警告 / <30 危险）。 */
export type HealthLevel = 'HEALTHY' | 'ATTENTION' | 'WARNING' | 'DANGER'
const healthLevels: { level: HealthLevel; tone: string; min: number }[] = [
  { level: 'HEALTHY', tone: 'green', min: 70 },
  { level: 'ATTENTION', tone: 'amber', min: 50 },
  { level: 'WARNING', tone: 'orange', min: 30 },
  { level: 'DANGER', tone: 'red', min: -Infinity },
]
export function healthLevel(hi: number | null | undefined) {
  if (hi == null || !Number.isFinite(hi)) return null
  const matched = healthLevels.find((item) => hi >= item.min) ?? healthLevels[healthLevels.length - 1]
  return { level: matched.level, tone: matched.tone, label: pointStatusLabel(matched.level) }
}

/** 最后一条带特征量的行。 */
export function latestRow(rows: ChannelSeriesRow[]): ChannelSeriesRow | null {
  for (let i = rows.length - 1; i >= 0; i--) {
    const metrics = rows[i]?.metrics
    if (metrics && Object.keys(metrics).length) return rows[i]
  }
  return null
}
/** 最后一条含指定特征的行。 */
export function latestRowWith(rows: ChannelSeriesRow[], feature: string): ChannelSeriesRow | null {
  for (let i = rows.length - 1; i >= 0; i--) {
    const value = rows[i]?.metrics?.[feature]
    if (typeof value === 'number' && Number.isFinite(value)) return rows[i]
  }
  return null
}
/** 最近一次已评分的健康指数。 */
export function latestHealthIndex(rows: ChannelSeriesRow[]): number | null {
  for (let i = rows.length - 1; i >= 0; i--) {
    const value = rows[i]?.health_index
    if (typeof value === 'number' && Number.isFinite(value)) return value
  }
  return null
}

/**
 * 把实时帧并入时序：同时间戳覆盖、不早于末行的帧才追加（保持升序）。
 * windowMs 为所选时间窗时长：早于窗口的帧丢弃，窗口外的旧行一并裁掉——实时并入不再无界增长。
 */
export function mergeLiveRow(rows: ChannelSeriesRow[], frame: ModalityStreamFrameBase, windowMs?: number, now = Date.now()): ChannelSeriesRow[] {
  const metrics = frame?.metrics
  if (!metrics || typeof metrics !== 'object' || !Number.isFinite(frame.timestamp)) return rows
  const row: ChannelSeriesRow = { timestamp: frame.timestamp, health_index: frame.health_index ?? null, status: frame.status || frame.alarm_level || '', data_quality: 0, metrics: { ...metrics } }
  let kept = rows
  if (windowMs != null) {
    const start = now - windowMs
    if (row.timestamp < start) return rows
    if (rows.length && rows[0].timestamp < start) kept = rows.filter((item) => item.timestamp >= start)
  }
  const last = kept[kept.length - 1]
  if (last && row.timestamp < last.timestamp) return kept === rows ? rows : kept
  return [...(last && row.timestamp === last.timestamp ? kept.slice(0, -1) : kept), row]
}

/** 对照表“当前值”的数据来源：实时帧优先；否则取快照与最近时序行中较新的一方（没有实时帧时不再整列为空）。 */
export function currentMetrics(source: {
  frame?: ModalityStreamFrameBase | null
  snapshot?: { metrics?: Record<string, number> | null; timestamp?: number | null } | null
  rows?: ChannelSeriesRow[] | null
}): Record<string, number> {
  const row = latestRow(source.rows ?? [])
  const snapshot = source.snapshot
  const snapshotIsNewer = !!snapshot && (snapshot.timestamp ?? 0) >= (row?.timestamp ?? 0)
  const older = snapshotIsNewer ? row?.metrics : snapshot?.metrics
  const newer = snapshotIsNewer ? snapshot?.metrics : row?.metrics
  return { ...older, ...newer, ...(source.frame?.metrics ?? {}) }
}

export interface FeatureComparison {
  feature: string
  label: string
  unit: string
  current: number | null
  baseline: number | null
  ratio: number | null
  cv: number | null
  status: string | null
  tone: string
}
/** 当前特征值 vs 基线 P50：偏离基线 ≥15% 标橙、≥30% 标红（lowerIsWorse 特征反向判断）；无基线为灰。 */
export function compareWithBaseline(current: Record<string, number> | null | undefined, baseline: ChannelBaseline | null | undefined, order: string[] = [], lowerIsWorse: string[] = []): FeatureComparison[] {
  const states = new Map((baseline?.featureStates ?? []).map((state) => [state.featureKey, state]))
  const keys: string[] = []
  for (const key of [...order, ...states.keys(), ...Object.keys(current ?? {})]) if (!keys.includes(key)) keys.push(key)
  return keys.map((feature) => {
    const state = states.get(feature)
    const value = current?.[feature]
    const currentValue = typeof value === 'number' && Number.isFinite(value) ? value : null
    const base = state?.baselineP50 ?? null
    const ratio = ratioOf(currentValue, base)
    const deviation = ratio == null ? null : (lowerIsWorse.includes(feature) ? 1 - ratio : ratio - 1)
    return {
      feature,
      ...featureInfo(feature),
      current: currentValue,
      baseline: base,
      ratio,
      cv: typeof state?.cvValue === 'number' && Number.isFinite(state.cvValue) ? state.cvValue : null,
      status: state?.status ?? null,
      tone: deviation == null ? 'gray' : deviation >= 0.3 ? 'red' : deviation >= 0.15 ? 'amber' : 'green',
    }
  })
}
