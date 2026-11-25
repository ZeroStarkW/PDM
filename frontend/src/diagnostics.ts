import type { DiagnosticMetric, DiagnosticOperator } from '@/api/types'

type MetricDetails = { label: string; unit: string; usesOrder?: boolean; allowsNegative?: boolean }

export const diagnosticMetrics: Record<DiagnosticMetric, MetricDetails> = {
  FREQ_1X_AMP: { label: '1X 振幅', unit: 'mm/s' },
  FREQ_1X_RATIO: { label: '1X 振幅 / 1X 基线', unit: '倍' },
  FREQ_2X_AMP: { label: '2X 振幅', unit: 'mm/s' },
  FREQ_1X_OVER_2X_RATIO: { label: '1X / 2X 振幅比', unit: '倍' },
  FREQ_2X_OVER_1X_RATIO: { label: '2X / 1X 振幅比', unit: '倍' },
  V_RMS: { label: '振动速度 RMS', unit: 'mm/s' },
  A_PEAK: { label: '加速度峰值', unit: 'm/s²' },
  A_KURTOSIS: { label: '加速度峭度', unit: '无量纲' },
  ENVELOPE_RMS: { label: '包络 RMS', unit: '上报单位' },
  TEMPERATURE: { label: '温度', unit: '°C', allowsNegative: true },
  ORDER_PEAK_AMPLITUDE: { label: '指定阶次谱峰振幅', unit: '上报单位', usesOrder: true },
  ORDER_PEAK_SNR: { label: '指定阶次谱峰信噪比', unit: '无量纲', usesOrder: true },
  MCSA_SIDEBAND_GAP_DB: { label: '电流边频带 / 载波间距', unit: 'dB' },
  MCSA_SLIP_PCT: { label: '电机转差率', unit: '%' },
  TRIP_DURATION_RATIO: { label: '分闸时间 / 基线', unit: '倍' },
  COIL_CURRENT_RATIO: { label: '线圈峰值电流 / 基线', unit: '倍' },
  CLOSING_VELOCITY_RATIO: { label: '合闸速度 / 基线', unit: '倍' },
  CONTACT_WEAR_RATIO: { label: '触头磨损率', unit: '0–1' },
  VALVE_DEADBAND_RATIO: { label: '回环死区 / 基线', unit: '倍' },
  VALVE_LEAK_RMS: { label: '超声泄漏 RMS', unit: 'RMS' },
  VALVE_TRAVEL_TIME_RATIO: { label: '行程滞后 / 基线', unit: '倍' },
  VALVE_FRICTION_RATIO: { label: '填料摩擦力 / 基线', unit: '倍' },
}

const legacyVibrationMetrics: DiagnosticMetric[] = [
  'FREQ_1X_AMP', 'FREQ_1X_RATIO', 'FREQ_2X_AMP', 'FREQ_1X_OVER_2X_RATIO', 'FREQ_2X_OVER_1X_RATIO',
  'V_RMS', 'A_PEAK', 'A_KURTOSIS', 'ENVELOPE_RMS', 'TEMPERATURE', 'ORDER_PEAK_AMPLITUDE', 'ORDER_PEAK_SNR',
]

const metricsByDeviceType: Record<string, DiagnosticMetric[]> = {
  ELECTRIC_MOTOR: ['MCSA_SIDEBAND_GAP_DB', 'MCSA_SLIP_PCT'],
  CIRCUIT_BREAKER: ['TRIP_DURATION_RATIO', 'COIL_CURRENT_RATIO', 'CLOSING_VELOCITY_RATIO', 'CONTACT_WEAR_RATIO'],
  CONTROL_VALVE: ['VALVE_DEADBAND_RATIO', 'VALVE_LEAK_RMS', 'VALVE_TRAVEL_TIME_RATIO', 'VALVE_FRICTION_RATIO'],
}

export function diagnosticMetricsForDeviceType(typeCode: string): DiagnosticMetric[] {
  const familyMetrics = metricsByDeviceType[typeCode]
  return familyMetrics ? [...familyMetrics, ...legacyVibrationMetrics] : [...legacyVibrationMetrics]
}

export const diagnosticOperators: Record<DiagnosticOperator, string> = { GT: '>', GTE: '≥', LT: '<', LTE: '≤' }

export const formatDiagnosticNumber = new Intl.NumberFormat('zh-CN', {
  maximumSignificantDigits: 6,
  useGrouping: false,
}).format

export function formatDiagnosticCondition(
  metric: DiagnosticMetric, operator: DiagnosticOperator, threshold: number, frequencyOrder?: number | null,
) {
  const { label, unit } = diagnosticMetrics[metric]
  const prefix = frequencyOrder == null ? '' : `${formatDiagnosticNumber(frequencyOrder)}X · `
  return `${prefix}${label} ${diagnosticOperators[operator]} ${formatDiagnosticNumber(threshold)} ${unit}`
}
