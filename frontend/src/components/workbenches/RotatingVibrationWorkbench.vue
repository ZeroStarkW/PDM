<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { EChartsType } from '@/charts'
import { ApiError } from '@/api/http'
import { captureWaveform, getChannelBaseline, getSpectrumBaseline, getWaveform, openLiveStream } from '@/api/pdm'
import type { ChannelBaseline, FaultLine, SpectrumBandView, SpectrumBaseline, SpectrumFrame, SpectrumKindBaseline } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/ui'
import { formatNumber, formatTime } from '@/modality'
import { chartTheme, ensureChart, errorMessage, useStreamFallback, type WorkbenchProps } from './shared'

/**
 * 旋转振动专家工作台（文档 §8.1）：
 * 同一帧数据驱动的三联动视图 —— 时域示波器（缩放/卡尺）、FFT 频谱（1X~6X 谐波光标族、
 * 线性/dB 切换、1X~3X 基线 P50 参考段 + 倍率 k 读数）、希尔伯特包络谱（BPFO/BPFI/BSF 故障线 +
 * SNR 标注、BPFO/BPFI 基线参考段）。
 */
const props = defineProps<WorkbenchProps>()
const auth = useAuthStore()
const { theme } = useTheme()

const location = computed(() => props.context ?? null)
const captureNote = ref('')
const busy = ref(false)

/** 设备额定转速：REST 快照不携带转速，谐波光标按它解算（与后端 SSE 帧取值一致）。 */
const nominalRpm = computed(() => props.context.machine?.rpmNominal ?? 0)
const rpm = ref(nominalRpm.value)
const bearingModel = ref<string | null>(null)
const triggerType = ref('')
const sampledAt = ref('')
const unit = ref('m/s^2')
const samplingRate = ref(0)

const timeWaveform = ref<number[]>([])
const fftFreqs = ref<number[]>([])
const fftAmps = ref<number[]>([])
const envelopeFreqs = ref<number[]>([])
const envelopeAmps = ref<number[]>([])
const harmonics = ref<{ order: number; hz: number }[]>([])
const faultLines = ref<FaultLine[]>([])

// ==== 基线锚点：通道基线特征状态行（P50）叠加到谐波光标与包络故障线上 ====
/** 外框（WorkbenchView）已对所有测点取通道基线时直接复用，独立挂载才自行拉取。 */
const channelBaseline = ref<ChannelBaseline | null>(null)
/** 谐波阶次 → 基线特征编码（与后端 VibrationContractAdapter 的特征编码一致）。 */
const HARMONIC_BASELINE_CODES: Record<number, string> = { 1: 'freq_1x_amp', 2: 'freq_2x_amp', 3: 'feat_3x_amp' }
/** 故障线名 → 包络基线特征编码。 */
const FAULT_BASELINE_CODES: Record<string, string> = { BPFO: 'envelope_bpfo_amp', BPFI: 'env_bpfi_amp' }
/** 劣化判读：k = 当前幅值 / 基线 P50 达到拐点半衰倍率即标红（HI 打分 k_mid = 2.5，文档 §4.3）。 */
const DEGRADE_RATIO = 2.5

const baselineByCode = computed(() => {
  const map = new Map<string, { p50: number; provisional: boolean }>()
  for (const state of channelBaseline.value?.featureStates ?? []) {
    if (state.baselineP50 != null && Number.isFinite(state.baselineP50))
      map.set(state.featureKey, { p50: state.baselineP50, provisional: state.status !== 'ACTIVE' })
  }
  return map
})

/** 与后端 BandRms.of 同口径的窄带能量（±max(3%, 1.5 bin) 内幅值平方和 × 2/3 开方），用于算谐波倍率 k。 */
function bandAmp(freqs: number[], amps: number[], targetHz: number): number | null {
  if (freqs.length < 2 || freqs.length !== amps.length || targetHz <= 0) return null
  const binWidth = freqs[1] - freqs[0]
  if (!(binWidth > 0)) return null
  const halfWidth = Math.max(targetHz * 0.03, 1.5 * binWidth)
  let sum = 0
  for (let i = 0; i < freqs.length; i++) {
    if (Math.abs(freqs[i] - targetHz) <= halfWidth) sum += amps[i] * amps[i]
  }
  return Math.sqrt(sum * 2 / 3)
}

/** 1X~3X 谐波的基线对照：k = 当前窄带能量 / 基线 P50；无可比基线或能量不可算时 k 为 null。 */
const harmonicStates = computed(() => harmonics.value.map((harmonic) => {
  const code = HARMONIC_BASELINE_CODES[harmonic.order]
  const base = code ? baselineByCode.value.get(code) : undefined
  let k: number | null = null
  if (base) {
    const amp = bandAmp(fftFreqs.value, fftAmps.value, harmonic.hz)
    if (amp != null && base.p50 > 1e-12) k = amp / base.p50
  }
  return { harmonic, base, k, degraded: k != null && k >= DEGRADE_RATIO }
}))

/** BPFO/BPFI 故障线相对包络基线的倍率文案（故障线幅值后端已算好），无基线的故障线不出现在映射里。 */
const faultRatioText = computed(() => {
  const map = new Map<string, string>()
  for (const line of faultLines.value) {
    const code = FAULT_BASELINE_CODES[line.name]
    const base = code ? baselineByCode.value.get(code) : undefined
    if (base && base.p50 > 1e-12) map.set(line.name, ` · k=${(line.amp / base.p50).toFixed(1)}×`)
  }
  return map
})

/** 外框共享数据就绪即采用；无外框（独立挂载）时自行拉取。 */
watch(() => props.shared, (shared) => {
  if (!shared) { void refreshBaseline(); return }
  if (shared.status === 'READY') channelBaseline.value = shared.baseline
}, { immediate: true })

/** 独立挂载时自行拉取通道基线；失败静默——锚点属增强展示，不阻断三视图。 */
async function refreshBaseline() {
  try {
    channelBaseline.value = await getChannelBaseline(props.point.id)
  } catch {
    channelBaseline.value = null
  }
}

// ==== 频谱基线参考带（后端逐带 MAD 冻结的 1/6 倍频程健康参考，超上界的带标红） ====
const spectrumBaseline = ref<SpectrumBaseline | null>(null)
const bandEnabled = ref(true)

async function refreshSpectrumBaseline() {
  try {
    spectrumBaseline.value = await getSpectrumBaseline(props.point.id)
  } catch {
    spectrumBaseline.value = null
  }
}

function kindBaseline(kind: 'FFT' | 'ENVELOPE'): SpectrumKindBaseline | null {
  const item = spectrumBaseline.value?.kinds.find((entry) => entry.kind === kind) ?? null
  return item && item.status === 'ACTIVE' && item.bands.length ? item : null
}

/** 基线带状态文案（面板标题）：已锁定带数 / 学习进度 / 未学习。 */
function bandStatusText(kind: 'FFT' | 'ENVELOPE') {
  const item = spectrumBaseline.value?.kinds.find((entry) => entry.kind === kind)
  if (!item) return '未学习'
  const total = spectrumBaseline.value?.minimumSamples ?? item.sampleCount
  return item.status === 'ACTIVE' ? `已锁定 · ${item.bands.length} 带` : `学习中 ${item.sampleCount}/${total}`
}

/** 当前帧逐带能量（与后端 SpectrumBandGrid.aggregate 同口径：带内幅值平方和开方）。 */
function bandValues(freqs: number[], amps: number[], bands: SpectrumBandView[]): number[] {
  return bands.map((band) => {
    let sum = 0
    let covered = false
    for (let i = 0; i < freqs.length; i++) {
      if (freqs[i] <= band.lowHz || freqs[i] > band.highHz) continue
      sum += amps[i] * amps[i]
      covered = true
    }
    return covered ? Math.sqrt(sum) : Number.NaN
  })
}

/** 超出参考带上界的带（红点位置：带中心 Hz + 当前带能量；dB 模式下能量转 dB）。 */
function bandExceedances(freqs: number[], amps: number[], bands: SpectrumBandView[], useDb: boolean): [number, number][] {
  const values = bandValues(freqs, amps, bands)
  const points: [number, number][] = []
  for (let i = 0; i < bands.length; i++) {
    const value = values[i]
    if (!Number.isFinite(value) || value <= bands[i].upper) continue
    points.push([bands[i].centerHz, useDb ? 20 * Math.log10(Math.max(value, 1e-6)) : value])
  }
  return points
}

const fftScale = ref<'linear' | 'db'>('linear')
const caliperEnabled = ref(false)
const caliperA = ref<number | null>(null)
const caliperB = ref<number | null>(null)
const nextMarkerIsA = ref(true)
const caliperReadout = computed(() => {
  if (caliperA.value == null || caliperB.value == null || samplingRate.value <= 0) return null
  const dt = Math.abs(caliperB.value - caliperA.value)
  if (dt <= 1e-9) return null
  const a = Math.min(caliperA.value, caliperB.value)
  const b = Math.max(caliperA.value, caliperB.value)
  const i0 = Math.max(0, Math.floor(a * samplingRate.value))
  const i1 = Math.min(timeWaveform.value.length, Math.ceil(b * samplingRate.value))
  let min = Infinity, max = -Infinity
  for (let i = i0; i < i1; i++) {
    const value = timeWaveform.value[i]
    if (value < min) min = value
    if (value > max) max = value
  }
  return { dt, freq: 1 / dt, p2p: Number.isFinite(min) ? max - min : null }
})

const timeChartEl = ref<HTMLDivElement>()
const fftChartEl = ref<HTMLDivElement>()
const envelopeChartEl = ref<HTMLDivElement>()
const dataError = ref('')
/** 尚无波形快照（REST 404）：正常空态，不按错误展示；等待边缘上报或手动召唤。 */
const missing = ref(false)
let timeChart: EChartsType | undefined
let fftChart: EChartsType | undefined
let envelopeChart: EChartsType | undefined
let alive = true

const canDispatch = computed(() => auth.user?.roles.some((role) => role === 'ADMIN' || role === 'OPERATOR'))
const hasFrame = computed(() => timeWaveform.value.length > 0)
const envelopeSlice = computed(() => {
  const freqs: number[] = []
  const amps: number[] = []
  for (let i = 0; i < envelopeFreqs.value.length; i++) {
    if (envelopeFreqs.value[i] <= 500) {
      freqs.push(envelopeFreqs.value[i])
      amps.push(envelopeAmps.value[i] ?? 0)
    }
  }
  return { freqs, amps }
})
/** 包络谱说明：故障特征线（BPFO/BPFI/BSF 及 SNR）只由实时流随帧解算，REST 快照与轮询模式不携带——文案需如实说明。 */
const envelopeNote = computed(() => {
  if (liveState.value !== 'LIVE') return '当前为 REST 快照模式，故障特征线仅由实时流解算，暂不显示'
  if (!bearingModel.value) return '测点未绑定轴承型号，故障线不可用'
  return faultLines.value.length ? '轴承特征故障线自动覆盖，SNR≥5 高亮提示确诊' : '实时流暂未返回故障线（需测点绑定轴承型号）'
})

/** REST 快照：成功返回 true；404 视为“尚无波形”的空态；其余按错误提示。 */
async function refreshFrame(): Promise<boolean> {
  const point = props.point
  busy.value = true
  dataError.value = ''
  try {
    const view = await getWaveform(point.id)
    if (!alive) return false
    rpm.value = nominalRpm.value
    applySnapshot(view.triggerType, new Date(view.sampledAt).toLocaleString(), view.samplingRate, view.unit,
      view.waveform, view.fftFreqs, view.fftAmps, view.envelopeFreqs, view.envelopeAmps,
      harmonicsFromRpm(nominalRpm.value), [], point)
    missing.value = false
    return true
  } catch (e) {
    if (!alive) return false
    if (e instanceof ApiError && e.status === 404) {
      missing.value = true
      dataError.value = ''
    } else dataError.value = errorMessage(e, '波形快照加载失败')
    return false
  } finally {
    if (alive) busy.value = false
  }
}

/**
 * 实时流 + 轮询兜底（状态机见 shared.ts）：
 * 网络抖动交给浏览器自动重连；404/409 等确定性失败关闭连接并每 30 秒刷新 REST 快照，
 * 仅在一次快照刷新成功之后、且距上次尝试满 60 秒才重开 v1 流。
 */
const stream = useStreamFallback({
  labels: { WAITING: '已连接 · 等待帧', LIVE: '实时流 2Hz', POLLING: 'REST 快照轮询 · 每 30 秒' },
  refresh: () => refreshFrame(),
  open: (control) => openLiveStream(props.point.id, (frame) => { if (alive) handleFrame(frame) },
    () => control.onError?.(), () => control.onOpen?.()),
})
const { state: liveState, label: liveLabel, tone: liveTone } = stream

/** 手动刷新快照：成功后若仍处于轮询兜底，尝试（限频）恢复实时流；同时重取频谱基线参考带。 */
async function manualRefresh() {
  void refreshSpectrumBaseline()
  if (await refreshFrame()) stream.retryNow()
}

function handleFrame(frame: SpectrumFrame) {
  // 只接受本测点、时间戳有效的帧（防串流与坏帧）
  if (frame.point_id != null && frame.point_id !== props.point.id) return
  if (!Number.isFinite(frame.timestamp)) return
  stream.acceptLive()
  applyFrame(frame)
}

function harmonicsFromRpm(rpmValue: number) {
  const rotationHz = rpmValue > 0 ? rpmValue / 60 : 0
  return [1, 2, 3, 4, 5, 6].map((order) => ({ order, hz: rotationHz * order }))
}

function applyFrame(frame: SpectrumFrame) {
  const point = props.point
  dataError.value = ''
  missing.value = false
  // 实时帧没有“触发类型/快照时间”字段：分别以“实时流帧”和帧时间戳填充，避免读数栏显示为 —
  applySnapshot('实时流帧', formatTime(frame.timestamp), frame.sampling_rate, frame.unit, frame.time_waveform, frame.fft_freqs,
    frame.fft_amps, frame.envelope_freqs, frame.envelope_amps, frame.harmonics, frame.fault_lines, point)
  rpm.value = frame.rpm
  bearingModel.value = frame.bearing_model ?? null
}

function applySnapshot(trigger: string, when: string, rate: number, waveUnit: string,
  waveform: number[], fftF: number[], fftA: number[], envF: number[], envA: number[],
  harmonicList: { order: number; hz: number }[], faults: FaultLine[], point: WorkbenchProps['point']) {
  triggerType.value = trigger
  sampledAt.value = when
  samplingRate.value = rate
  unit.value = waveUnit
  timeWaveform.value = waveform
  fftFreqs.value = fftF
  fftAmps.value = fftA
  envelopeFreqs.value = envF
  envelopeAmps.value = envA
  harmonics.value = harmonicList.length ? harmonicList : harmonicsFromRpm(rpm.value)
  faultLines.value = faults
  bearingModel.value = bearingModel.value ?? point.bearingModel ?? null
  draw()
}

/** FFT 图标注：1X~6X 谐波光标族（k≥2.5 标红并标注倍率）+ 有基线特征的 1X~3X 基线 P50 参考短段（ACTIVE 紫 / 学习预览灰）。 */
function fftMarkLine(palette: ReturnType<typeof chartTheme>) {
  const data: unknown[] = []
  const rotationHz = rpm.value > 0 ? rpm.value / 60 : 0
  const useDb = fftScale.value === 'db'
  for (const state of harmonicStates.value) {
    const { harmonic, base, k, degraded } = state
    if (harmonic.hz <= 0 || harmonic.hz > 2000) continue
    data.push({
      xAxis: harmonic.hz,
      lineStyle: { color: degraded ? palette.red : palette.orange, type: 'dashed' as const, width: 1 },
      label: {
        formatter: degraded && k != null ? `${harmonic.order}X · k=${k.toFixed(1)} 劣化` : `${harmonic.order}X`,
        fontSize: 10,
      },
    })
    if (!base || rotationHz <= 0) continue
    const y = useDb ? 20 * Math.log10(Math.max(base.p50, 1e-6)) : base.p50
    const delta = rotationHz * 0.15
    const color = base.provisional ? palette.muted : palette.violet
    data.push([
      {
        coord: [harmonic.hz - delta, y],
        lineStyle: { color, type: 'dashed' as const, width: 1 },
        label: { formatter: `基线 ${formatNumber(y, useDb ? 0 : 2)}`, fontSize: 10, color },
      },
      { coord: [harmonic.hz + delta, y] },
    ])
  }
  return data.length ? { symbol: 'none', silent: true, data } : undefined
}

/** FFT 悬浮：当前频点读数 + 就近谐波（±max(3%, 1.5 bin)）的基线 P50 与倍率 k（只读当前频谱主系列）。 */
function fftTooltip(params: unknown, useDb: boolean, fftUnit: string) {
  const list = Array.isArray(params) ? params : [params]
  const value = (list.find((item) => (item as { seriesName?: string }).seriesName === '当前频谱') as
    { value?: [number, number] } | undefined)?.value
  if (!Array.isArray(value)) return ''
  const [freq, amp] = value
  const lines = [`${formatNumber(freq, 1)} Hz · ${formatNumber(amp, useDb ? 1 : 3)} ${fftUnit}`]
  const binWidth = fftFreqs.value.length >= 2 ? fftFreqs.value[1] - fftFreqs.value[0] : 0
  const near = harmonicStates.value.find((state) => state.harmonic.hz > 0
    && Math.abs(state.harmonic.hz - freq) <= Math.max(state.harmonic.hz * 0.03, 1.5 * binWidth))
  if (near?.base) {
    const kText = near.k != null ? ` · k=${near.k.toFixed(2)}×${near.degraded ? '（劣化）' : ''}` : ''
    lines.push(`${near.harmonic.order}X 基线 ${formatNumber(near.base.p50, 3)}${kText}`)
    if (near.base.provisional) lines.push('基线为学习预览值，尚未锁定')
  }
  return lines.join('\n')
}

/**
 * 参考带堆叠面积（下界透明 + 宽度填充）：学习/冻结值均为带中心 Hz 对齐的数组。
 * dB 模式下上下限同样换算，保证参考带与主谱同坐标系。
 */
function bandSeries(kind: 'FFT' | 'ENVELOPE', palette: ReturnType<typeof chartTheme>, useDb: boolean) {
  const band = kindBaseline(kind)
  if (!bandEnabled.value || !band) return []
  const toY = (value: number) => (useDb ? 20 * Math.log10(Math.max(value, 1e-6)) : value)
  const lower = band.bands.map((item) => [item.centerHz, toY(item.lower)])
  const width = band.bands.map((item) => [item.centerHz, Math.max(0, toY(item.upper) - toY(item.lower))])
  return [
    { name: '基线下限', type: 'line', stack: `band-${kind}`, data: lower, lineStyle: { opacity: 0 },
      areaStyle: { opacity: 0 }, showSymbol: false, silent: true, animation: false, legendHoverLink: false },
    { name: '健康基线带', type: 'line', stack: `band-${kind}`, data: width, lineStyle: { opacity: 0 },
      areaStyle: { color: palette.band }, showSymbol: false, silent: true, animation: false, legendHoverLink: false },
  ]
}

/** 包络图标注：BPFO/BPFI/BSF/FTF 故障线（SNR≥5 红）+ BPFO/BPFI 有基线时的 P50 参考短段（ACTIVE 紫 / 学习预览灰）。 */
function envelopeMarkLine(palette: ReturnType<typeof chartTheme>) {
  const data: unknown[] = []
  for (const line of faultLines.value) {
    if (line.hz > 500) continue
    data.push({
      xAxis: line.hz,
      lineStyle: { color: line.snr >= 5 ? palette.red : palette.muted, type: 'dashed' as const, width: 1 },
      label: { formatter: `${line.name}\nSNR ${line.snr.toFixed(1)}`, fontSize: 10 },
    })
    const code = FAULT_BASELINE_CODES[line.name]
    const base = code ? baselineByCode.value.get(code) : undefined
    if (!base) continue
    const delta = Math.max(line.hz * 0.1, 1.5)
    const color = base.provisional ? palette.muted : palette.violet
    data.push([
      {
        coord: [Math.max(0, line.hz - delta), base.p50],
        lineStyle: { color, type: 'dashed' as const, width: 1 },
        label: { formatter: `基线 ${formatNumber(base.p50, 3)}`, fontSize: 10, color },
      },
      { coord: [line.hz + delta, base.p50] },
    ])
  }
  return data.length ? { symbol: 'none', silent: true, data } : undefined
}

function draw() {
  const palette = chartTheme()
  const { axis, split, tooltip: tooltipStyle } = palette
  const caliperMarks = caliperA.value == null && caliperB.value == null ? [] : [
    ...(caliperA.value == null ? [] : [{ xAxis: caliperA.value }]),
    ...(caliperB.value == null ? [] : [{ xAxis: caliperB.value }]),
  ]

  if (timeChartEl.value) {
    timeChart = ensureChart(timeChart, timeChartEl.value)
    // 采样率已知时横轴为时间（s，刻度显示 ms）；未知时只能按采样点序号，坐标轴如实标注
    const hasRate = samplingRate.value > 0
    timeChart.setOption({
      tooltip: { trigger: 'axis', ...tooltipStyle, valueFormatter: (value: number) => `${formatNumber(value, 3)} ${unit.value}` },
      grid: { left: 52, right: 20, top: 26, bottom: 52 },
      dataZoom: [{ type: 'inside' }, { type: 'slider', height: 18, bottom: 8 }],
      xAxis: { type: 'value', name: hasRate ? 'ms' : '采样点', nameTextStyle: { color: axis },
        axisLabel: { color: axis, formatter: (value: number) => (hasRate ? (value * 1000).toFixed(0) : String(value)) },
        axisLine: { lineStyle: { color: split } } },
      yAxis: { type: 'value', name: unit.value, nameTextStyle: { color: axis },
        axisLabel: { color: axis, formatter: (value: number) => value.toFixed(1) },
        splitLine: { lineStyle: { color: split } } },
      series: [{
        type: 'line', showSymbol: false, animation: false,
        data: timeWaveform.value.map((value, index) => [hasRate ? index / samplingRate.value : index, value]),
        lineStyle: { color: palette.feature, width: 1 },
        itemStyle: { color: palette.feature },
        markLine: caliperMarks.length ? {
          symbol: 'none', silent: true, data: caliperMarks,
          lineStyle: { color: palette.marker, type: 'solid' as const, width: 1.5 },
          label: { formatter: (parameter: { value: number }) => `卡尺 ${Number(parameter.value).toFixed(4)}s`, fontSize: 10 },
        } : undefined,
      }],
    })
    timeChart.getZr().off('click')
    timeChart.getZr().on('click', (event: { offsetX: number; offsetY: number }) => {
      if (!caliperEnabled.value || !timeChart) return
      const pixel = timeChart.convertFromPixel({ xAxisIndex: 0 }, [event.offsetX, event.offsetY])
      if (!pixel || pixel[0] == null) return
      const time = pixel[0] as number
      if (nextMarkerIsA.value) { caliperA.value = time; caliperB.value = null }
      else caliperB.value = time
      nextMarkerIsA.value = !nextMarkerIsA.value
      draw()
    })
  }

  if (fftChartEl.value) {
    fftChart = ensureChart(fftChart, fftChartEl.value)
    const useDb = fftScale.value === 'db'
    const amps = useDb
      ? fftAmps.value.map((value) => 20 * Math.log10(Math.max(value, 1e-6)))
      : fftAmps.value
    const fftUnit = useDb ? 'dB' : unit.value
    fftChart.setOption({
      tooltip: { trigger: 'axis', ...tooltipStyle, formatter: (params: unknown) => fftTooltip(params, useDb, fftUnit) },
      grid: { left: 56, right: 20, top: 26, bottom: 26 },
      xAxis: { type: 'value', name: 'Hz', nameTextStyle: { color: axis },
        max: Math.min(2000, fftFreqs.value.length ? fftFreqs.value[fftFreqs.value.length - 1] : 2000),
        axisLabel: { color: axis }, axisLine: { lineStyle: { color: split } } },
      yAxis: { type: 'value', name: fftUnit, nameTextStyle: { color: axis },
        axisLabel: { color: axis, formatter: (value: number) => value.toFixed(useDb ? 0 : 2) },
        splitLine: { lineStyle: { color: split } } },
      series: [{
        name: '当前频谱', type: 'line', showSymbol: false, animation: false,
        data: fftFreqs.value.map((freq, index) => [freq, amps[index]]),
        lineStyle: { color: palette.blue, width: 1 },
        itemStyle: { color: palette.blue },
        markLine: fftMarkLine(palette),
      }, ...bandSeries('FFT', palette, useDb), {
        name: '超参考带', type: 'scatter', symbolSize: 7, animation: false, silent: true,
        data: bandExceedances(fftFreqs.value, fftAmps.value, kindBaseline('FFT')?.bands ?? [], useDb),
        itemStyle: { color: palette.red },
      }],
      // 基线带开关/冻结状态变化会改变 series 数量，replaceMerge 防止旧 series 残留（同 ChannelTrendChart）
    }, { replaceMerge: ['series'] })
  }

  if (envelopeChartEl.value) {
    envelopeChart = ensureChart(envelopeChart, envelopeChartEl.value)
    envelopeChart.setOption({
      tooltip: { trigger: 'axis', ...tooltipStyle, formatter: (params: unknown) => {
        const list = Array.isArray(params) ? params : [params]
        const value = (list.find((item) => (item as { seriesName?: string }).seriesName === '包络谱') as
          { value?: [number, number] } | undefined)?.value
        return Array.isArray(value) ? `${formatNumber(value[0], 1)} Hz · ${formatNumber(value[1], 3)}` : ''
      } },
      grid: { left: 56, right: 20, top: 26, bottom: 26 },
      xAxis: { type: 'value', name: 'Hz', max: 500, nameTextStyle: { color: axis },
        axisLabel: { color: axis }, axisLine: { lineStyle: { color: split } } },
      yAxis: { type: 'value', name: '包络幅值', nameTextStyle: { color: axis },
        axisLabel: { color: axis, formatter: (value: number) => value.toFixed(3) },
        splitLine: { lineStyle: { color: split } } },
      series: [{
        name: '包络谱', type: 'line', showSymbol: false, animation: false,
        data: envelopeSlice.value.freqs.map((freq, index) => [freq, envelopeSlice.value.amps[index]]),
        lineStyle: { color: palette.violet, width: 1 },
        itemStyle: { color: palette.violet },
        markLine: envelopeMarkLine(palette),
      }, ...bandSeries('ENVELOPE', palette, false), {
        name: '超参考带', type: 'scatter', symbolSize: 7, animation: false, silent: true,
        data: bandExceedances(envelopeSlice.value.freqs, envelopeSlice.value.amps,
          kindBaseline('ENVELOPE')?.bands ?? [], false),
        itemStyle: { color: palette.red },
      }],
    }, { replaceMerge: ['series'] })
  }
}

function placeCaliperHint() {
  caliperEnabled.value = !caliperEnabled.value
  nextMarkerIsA.value = true
  draw()
}

function clearCaliper() {
  caliperA.value = null
  caliperB.value = null
  nextMarkerIsA.value = true
}

async function dispatchCapture() {
  const point = props.point
  captureNote.value = ''
  try {
    const response = await captureWaveform(point.id)
    captureNote.value = response.message || '已下发召唤指令'
  } catch (e) {
    captureNote.value = e instanceof Error ? e.message : '召唤失败'
  }
}

function faultBadge(line: FaultLine) {
  return line.snr >= 5 ? 'fault-hit' : 'fault-quiet'
}

function resize() {
  timeChart?.resize()
  fftChart?.resize()
  envelopeChart?.resize()
}

watch(fftScale, draw)
watch(theme, draw)
watch(bandEnabled, draw)
watch([caliperA, caliperB], draw)
onMounted(async () => {
  window.addEventListener('resize', resize)
  // 先 REST 拉一次快照保底渲染，再开 SSE 进入 2Hz 实时流；尚无快照时流端点必然 404/409，直接进入轮询兜底
  stream.connecting()
  void refreshSpectrumBaseline()
  await refreshFrame()
  if (!alive) return
  if (missing.value) stream.enterPolling()
  else stream.start()
})
onBeforeUnmount(() => {
  alive = false
  stream.stop()
  timeChart?.dispose()
  fftChart?.dispose()
  envelopeChart?.dispose()
  window.removeEventListener('resize', resize)
})
</script>

<template>
  <div class="vibration-workbench">
    <section class="panel toolbar-panel">
      <div class="panel-heading">
        <div><h3>旋转振动专家视图</h3><p>时域示波器 · FFT 谐波光标族 · 希尔伯特包络谱故障线，同一帧数据联动刷新</p></div>
      </div>
      <div class="workbench-actions">
        <span v-if="liveLabel" :class="['status-tag', liveTone]"><i></i>{{ liveLabel }}</span>
        <button class="quiet-button" :class="{ active: caliperEnabled }" :disabled="!hasFrame || samplingRate <= 0"
          :title="samplingRate <= 0 ? '采样率未知，无法按时间测量' : ''" @click="placeCaliperHint">{{ caliperEnabled ? '卡尺模式开启中' : '峰峰值卡尺' }}</button>
        <button v-if="caliperEnabled || caliperA != null || caliperB != null" class="quiet-button" @click="clearCaliper">清除卡尺</button>
        <select v-model="fftScale" class="compact-select" aria-label="FFT 坐标刻度"><option value="linear">FFT 线性坐标</option><option value="db">FFT 对数 (dB)</option></select>
        <button class="quiet-button" :class="{ active: bandEnabled }" title="显示/隐藏频谱基线参考带（超出上界的频带标红）" @click="bandEnabled = !bandEnabled">基线带</button>
        <button v-if="canDispatch" class="quiet-button" :disabled="busy" @click="dispatchCapture">⚡ 召唤波形</button>
        <button class="quiet-button" :disabled="busy" @click="manualRefresh">↻ 刷新快照</button>
      </div>
    </section>
    <div v-if="captureNote" class="notice">{{ captureNote }}</div>
    <!-- 尚无波形（404）是正常空态：没有任何帧时只显示下方的空状态；上一帧仍在显示时才提示“最新刷新未取到快照” -->
    <div v-if="missing && hasFrame" class="notice info-notice"><b>未取到最新波形快照</b><span>当前显示的是上一次的数据；等待边缘上报，或点击“召唤波形”触发一次采集。</span></div>
    <div v-if="dataError" class="notice error-notice">{{ dataError }}</div>

    <div class="panel frame-meta">
      <div v-if="location?.machine"><dt>测点</dt><dd>{{ location.machine.name }} · {{ point.name }}<small>{{ location.factory?.code }} / {{ location.machine.code }} / {{ point.code }}</small></dd></div>
      <div><dt>转速</dt><dd>{{ rpm.toFixed(0) }} RPM</dd></div>
      <div><dt>轴承型号</dt><dd>{{ bearingModel || '未绑定' }}</dd></div>
      <div><dt>采样率</dt><dd>{{ samplingRate ? `${samplingRate} Hz` : '—' }}<small>{{ timeWaveform.length }} 点</small></dd></div>
      <div><dt>触发类型</dt><dd>{{ triggerType || '—' }}</dd></div>
      <div><dt>快照时间</dt><dd>{{ sampledAt || '—' }}</dd></div>
      <div v-if="caliperReadout" class="caliper-readout"><dt>卡尺读数</dt><dd>Δt = {{ (caliperReadout.dt * 1000).toFixed(2) }} ms ≈ {{ caliperReadout.freq.toFixed(1) }} Hz<span v-if="caliperReadout.p2p != null"> · 峰峰值 {{ caliperReadout.p2p.toFixed(2) }} {{ unit }}</span></dd></div>
    </div>

    <div v-if="missing && !hasFrame" class="panel empty-state"><div>∿</div><b>尚无波形快照</b><p>等待边缘上报，或点击“召唤波形”触发一次采集；快照就绪后自动恢复实时流。</p></div>
    <template v-else>
      <section class="panel chart-panel">
        <div class="panel-heading"><div><h3>视图 1 · 时域示波器</h3><p>滚轮缩放 / 拖拽平移；开启卡尺后点击两处波峰读取 Δt 与等效频率</p></div></div>
        <div ref="timeChartEl" class="workbench-chart tall"></div>
      </section>

      <!-- 两个频域视图并列：同为频谱，横向铺排比纵向堆叠紧凑，工作台整体高度约减半 -->
      <div class="spectrum-grid">
  <section class="panel chart-panel">
        <div class="panel-heading"><div><h3>视图 2 · FFT 频谱仪</h3><p>{{ fftScale === 'db' ? '对数 (dB) 坐标' : '线性坐标' }} · 1X~6X 转频谐波光标族 · 短横线为基线 P50 参考（k = 当前/基线，≥{{ DEGRADE_RATIO }} 标红劣化）· 基线带{{ bandStatusText('FFT') }}</p></div></div>
    <div ref="fftChartEl" class="workbench-chart"></div>
  </section>

  <section class="panel chart-panel">
    <div class="panel-heading">
          <div><h3>视图 3 · 希尔伯特包络谱</h3><p>0~500 Hz 低频区 · 基线带{{ bandStatusText('ENVELOPE') }} · {{ envelopeNote }}</p></div>
      <div v-if="faultLines.length" class="fault-legend">
        <span v-for="line in faultLines.filter(f => f.hz <= 500)" :key="line.name" :class="['status-tag', faultBadge(line)]">
          {{ line.name }} {{ line.hz.toFixed(1) }}Hz · SNR {{ line.snr.toFixed(1) }}{{ faultRatioText.get(line.name) ?? '' }}
        </span>
      </div>
    </div>
    <div ref="envelopeChartEl" class="workbench-chart"></div>
  </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.vibration-workbench { display: grid; gap: 14px; }
.toolbar-panel { padding: 16px 18px 18px; display: grid; gap: 12px; }
.workbench-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.quiet-button.active { border-color: var(--teal); color: var(--teal); }
.frame-meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px; padding: 16px 18px; }
.frame-meta dt { color: var(--muted); font-size: 10px; }
.frame-meta dd { margin: 4px 0 0; font-size: 13px; }
.frame-meta dd small { display: block; color: var(--muted); font-size: 10px; }
.caliper-readout dd { color: var(--amber); }
.workbench-chart { height: 250px; width: 100%; }
.workbench-chart.tall { height: 300px; }
.spectrum-grid { display: grid; grid-template-columns: minmax(0, 1fr); gap: 14px; }
/* 主列够宽时两个频谱并列（容器查询沿用外框 workbench-main 的宽度） */
@container (min-width: 1160px) { .spectrum-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
.fault-legend { display: flex; gap: 6px; flex-wrap: wrap; }
.fault-hit { color: var(--red); }
.fault-quiet { color: var(--muted); }
</style>
