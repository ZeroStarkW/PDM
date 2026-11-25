import type { Telemetry, TelemetryFreshness } from '@/api/types'

const statusLabels: Record<string, string> = {
  HEALTHY: '正常',
  ATTENTION: '关注',
  WARNING: '警告',
  DANGER: '危险',
  SENSOR_CHECK: '传感器异常',
  STOPPED: '停机',
  STOPPED_OR_SENSOR_CHECK: '停机或传感器异常',
  MAINTENANCE: '检修静默',
  RETIRED: '已停用',
  CONDITION_REJECTED: '工况不可评估',
  BASELINE_LEARNING: '基线学习中',
  BASELINE_DIVERGENCE: '基线学习未收敛',
  ANALYSIS_BUFFERING: '等待分析',
  ANALYSIS_PENDING: '等待分析',
  SPIKE_SUPPRESSED: '尖峰已过滤',
  HEALTHY_OR_BASELINE_LEARNING: '暂无健康评估',
  NO_DATA: '暂无采样',
  NO_POINTS: '暂无测点',
}

const freshnessStates: Record<TelemetryFreshness, { label: string; tone: string }> = {
  NO_DATA: { label: '暂无上报', tone: 'gray' },
  ONLINE: { label: '在线', tone: 'green' },
  STALE: { label: '数据过期', tone: 'amber' },
}

/** 多模态通道基线状态（v2 通道/特征级基线状态机）。 */
const baselineStates: Record<string, { label: string; tone: string }> = {
  ACTIVE: { label: '已锁定', tone: 'green' },
  LEARNING: { label: '学习中', tone: 'amber' },
  PROVISIONAL: { label: '学习中（暂定）', tone: 'amber' },
  DIVERGED: { label: '学习未收敛', tone: 'orange' },
  PENDING_RESET: { label: '待重置', tone: 'red' },
}

const qualityLabels: Record<number, string> = {
  0: '正常',
  1: '电压不足',
  2: '超量程削波',
  3: '探头异常',
}

export function pointStatusLabel(status?: string | null) {
  return status ? statusLabels[status] ?? status : '暂无采样'
}

/** 点健康状态色调（与 statusLabels 对应）：学习中 / 等待分析为信息性蓝色，工况不可评估与停机为中性灰。 */
const statusTones: Record<string, string> = {
  HEALTHY: 'green', ATTENTION: 'amber', WARNING: 'orange', DANGER: 'red',
  SENSOR_CHECK: 'orange', STOPPED: 'gray', STOPPED_OR_SENSOR_CHECK: 'gray',
  MAINTENANCE: 'gray', RETIRED: 'gray',
  CONDITION_REJECTED: 'gray', BASELINE_LEARNING: 'blue', BASELINE_DIVERGENCE: 'amber',
  ANALYSIS_BUFFERING: 'blue', ANALYSIS_PENDING: 'blue', SPIKE_SUPPRESSED: 'gray',
  HEALTHY_OR_BASELINE_LEARNING: 'blue', NO_DATA: 'gray', NO_POINTS: 'gray',
}
/** 健康状态色调的唯一出处（原先各视图各持一份不完整的映射，未登记状态会落到默认蓝色）。 */
export function statusTone(status?: string | null) { return (status && statusTones[status]) || 'blue' }

const alertLevels: Record<string, { label: string; tone: string }> = {
  DANGER: { label: '危险', tone: 'red' },
  WARNING: { label: '警告', tone: 'orange' },
}
/** 告警级别 → 中文与色调；未登记级别原样显示。 */
export function alertLevelState(level?: string | null) { return (level && alertLevels[level]) || { label: level || '告警', tone: 'orange' } }

const workOrderStatuses: Record<string, { label: string; tone: string }> = {
  OPEN: { label: '待处理', tone: 'amber' },
  IN_PROGRESS: { label: '处理中', tone: 'blue' },
  CLOSED: { label: '已闭环', tone: 'green' },
  SUPPRESSED: { label: '已抑制', tone: 'gray' },
}
/** 维修工单状态 → 中文与色调；未登记状态原样显示。 */
export function workOrderStatusState(status?: string | null) { return (status && workOrderStatuses[status]) || { label: status || '未知', tone: 'gray' } }

export function freshnessState(freshness?: TelemetryFreshness) {
  return freshness ? freshnessStates[freshness] : freshnessStates.NO_DATA
}

export function baselineState(status?: string | null) {
  return status && baselineStates[status] ? baselineStates[status] : { label: status || '暂无基线', tone: 'gray' }
}

export function qualityLabel(code?: number | null) {
  return code == null ? '暂无质量码' : qualityLabels[code] ?? `未知质量码 ${code}`
}

export function telemetryTime(value?: string | null) {
  return value ? new Date(value).toLocaleString() : '—'
}

/** 定点小数（保持 toFixed 的位数）；缺值（如未上报的可选特征 aPeak）显示为 —。 */
export function fixed(value: number | null | undefined, digits = 2): string {
  return value == null || !Number.isFinite(value) ? '—' : value.toFixed(digits)
}

export function telemetryTooltip(params: unknown, samples: Telemetry[]) {
  if (!Array.isArray(params) || !params.length) return ''
  const index = params[0]?.dataIndex
  const sample = typeof index === 'number' ? samples[index] : undefined
  if (!sample) return ''

  return [
    telemetryTime(sample.timestamp),
    `振动速度 RMS  ${fixed(sample.vRms)} mm/s`,
    `健康指数  ${fixed(sample.healthIndex)}`,
    pointStatusLabel(sample.status),
    sample.stopped && sample.status !== 'STOPPED' ? '此区间包含停机采样' : '',
    sample.dataQuality > 0 ? `数据质量：${qualityLabel(sample.dataQuality)}` : '',
  ].filter(Boolean).join('\n')
}
