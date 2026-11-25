<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { EChartsType } from '@/charts'
import { ApiError } from '@/api/http'
import { getChannelBaseline, getChannelSeries, getLatestSnapshot } from '@/api/pdm'
import type { ChannelBaseline, ChannelSeriesRow, ChannelSnapshot, MetricsFrame, StrokeEventFrame } from '@/api/types'
import { useTheme } from '@/composables/ui'
import { compareWithBaseline, currentMetrics, formatNumber, formatRatio, formatTime, healthLevel, latestHealthIndex, latestRow, timeAgo } from '@/modality'
import { baselineState } from '@/telemetry-status'
import { chartTheme, ensureChart, errorMessage, numericFields, ratioHint, type WorkbenchProps } from './shared'

/**
 * 高压断路器机械特性专家视图（设计文档 §5 情况 B）：
 * 线圈电流波形 vs 动触头机械行程同步双 Y 轴曲线；自动标注分闸起动点、触头分离点、
 * 缓冲制动点与动作总时差；当前动作特征与通道基线 P50 对照。
 */
const props = defineProps<WorkbenchProps>()
const { theme } = useTheme()
const chartEl = ref<HTMLDivElement>()
let chart: EChartsType | undefined

// ==== 数据来源：外框已取到的快照 / 基线 / 时序直接复用（避免同一请求发两次）；外框没有给出快照结论时才由本视图自行拉取 ====
const ownSnapshot = ref<ChannelSnapshot | null>(null)
const ownBaseline = ref<ChannelBaseline | null>(null)
const ownRows = ref<ChannelSeriesRow[]>([])
const ownMissing = ref(false)
const error = ref('')
const busy = ref(false)
const liveStroke = ref<StrokeEventFrame | null>(null)
const liveMetrics = ref<MetricsFrame | null>(null)

const usesShared = computed(() => props.shared?.status === 'READY' && props.shared.snapshot !== undefined)
const waitingShared = computed(() => props.shared?.status === 'LOADING')
const snapshot = computed(() => (usesShared.value ? props.shared?.snapshot ?? null : ownSnapshot.value))
const baseline = computed(() => (usesShared.value ? props.shared?.baseline ?? null : ownBaseline.value))
const rows = computed(() => (usesShared.value ? props.shared?.rows ?? [] : ownRows.value))
const missing = computed(() => (usesShared.value ? props.shared?.snapshot === null : ownMissing.value))
const loading = computed(() => busy.value || waitingShared.value)

const annotationKeys = ['tripStartMs', 'contactSeparationMs', 'brakingMs', 'totalTimeMs', 'peakCurrentA', 'fullStrokeMm', 'maxVelocityMps']
const annotationLabels: Record<string, string> = {
  tripStartMs: '分闸起动点',
  contactSeparationMs: '触头分离点',
  brakingMs: '缓冲制动点',
  totalTimeMs: '动作总时差',
  peakCurrentA: '峰值电流',
  fullStrokeMm: '全行程',
  maxVelocityMps: '最大速度',
}
const annotationUnits: Record<string, string> = {
  tripStartMs: 'ms', contactSeparationMs: 'ms', brakingMs: 'ms', totalTimeMs: 'ms',
  peakCurrentA: 'A', fullStrokeMm: 'mm', maxVelocityMps: 'm/s',
}
/** 快照触发类型（后端枚举码）→ 中文；实时帧固定标注为实时动作帧。 */
const triggerLabels: Record<string, string> = { THRESHOLD_EXCEEDED: '阈值触发', SCHEDULED: '定时采集', ON_DEMAND: '手动召唤' }

/** 曲线视图模型：快照（含 previewCurve 兜底）与实时动作帧归一成同一形状。 */
const view = computed(() => {
  const frame = liveStroke.value
  const snap = snapshot.value
  const frameCurves = frame?.curves ?? {}
  const snapCurves = snap?.curves ?? {}
  const snapCoil = Array.isArray(snapCurves.coil_current_a) ? snapCurves.coil_current_a : (snap?.previewCurve ?? [])
  const frameCoil = Array.isArray(frameCurves.coil_current_a) && frameCurves.coil_current_a.length ? frameCurves.coil_current_a : null
  const frameTravel = Array.isArray(frameCurves.travel_stroke_mm) ? frameCurves.travel_stroke_mm : []
  // 实时帧带曲线才接管视图；帧里缺线圈电流曲线时回落到 REST 已展示的快照曲线（含 previewCurve 兜底），不因一帧缺列而丢图
  const useFrame = !!frame && (frameCoil != null || frameTravel.length > 0)
  const coil = useFrame ? (frameCoil ?? snapCoil) : snapCoil
  const travel = useFrame ? frameTravel : (Array.isArray(snapCurves.travel_stroke_mm) ? snapCurves.travel_stroke_mm : [])
  const rate = snap?.samplingRate ?? null
  // 时间轴：优先曲线自带 time_axis_ms；否则按通道采样率推导 (ms)，采样率未知则退化为逐点序号——坐标轴按实际情况标注
  const axisFor = (length: number, curves: Record<string, number[]>) => {
    const explicit = Array.isArray(curves.time_axis_ms) && curves.time_axis_ms.length ? curves.time_axis_ms : []
    if (explicit.length) return { axis: explicit, inMs: true }
    const hasRate = !!rate && rate > 0
    return { axis: Array.from({ length }, (_, index) => (hasRate ? Number(((index / (rate as number)) * 1000).toFixed(3)) : index)), inMs: hasRate }
  }
  const coilAxis = axisFor(coil.length, useFrame && frameCoil ? frameCurves : snapCurves)
  const travelAxis = axisFor(travel.length, useFrame ? frameCurves : snapCurves)
  const annotations = numericFields(useFrame ? frame?.annotations : snap?.annotations, annotationKeys)
  // “当前值”：实时帧 > 快照 / 最近时序行中较新者（没有实时帧时不再整列为空）
  const latest = frame ?? liveMetrics.value
  const metrics = currentMetrics({ frame: latest, snapshot: snap, rows: rows.value })
  const annotationSource: Record<string, unknown> | null | undefined = useFrame ? frame?.annotations : snap?.annotations
  return {
    coil, travel, annotations, metrics,
    coilAxis: coilAxis.axis, travelAxis: travelAxis.axis,
    timeUnit: coilAxis.inMs || travelAxis.inMs ? 'ms' : '采样点',
    coilUnit: frame?.curve_units?.coil_current_a ?? snap?.curveUnits?.coil_current_a ?? 'A',
    travelUnit: frame?.curve_units?.travel_stroke_mm ?? snap?.curveUnits?.travel_stroke_mm ?? 'mm',
    triggerType: useFrame ? '实时动作帧' : snap?.triggerType ? triggerLabels[snap.triggerType] ?? snap.triggerType : '',
    sampledAtLabel: formatTime(useFrame ? frame?.timestamp ?? null : snap ? new Date(snap.sampledAt).getTime() : null),
    sampledAtRaw: useFrame ? frame?.timestamp ?? null : snap ? new Date(snap.sampledAt).getTime() : null,
    samplingRate: useFrame ? null : snap?.samplingRate ?? null,
    pointsCount: useFrame ? (frameCoil?.length ?? frameTravel.length) : snap?.pointsCount ?? null,
    storage: useFrame ? '实时流' : snap?.storageBackend ?? snap?.snapshotUrl ?? snap?.storageRef ?? '—',
    healthIndex: latest?.health_index ?? snap?.healthIndex ?? latestHealthIndex(rows.value) ?? null,
    annotationsMissing: Object.keys(annotationSource ?? {}).length === 0,
  }
})

const hasCurve = computed(() => view.value.coil.length > 0 || view.value.travel.length > 0)
const health = computed(() => healthLevel(view.value.healthIndex))
const metricsNote = computed(() => {
  if (liveStroke.value || liveMetrics.value) return '当前值来自实时帧'
  const snapshotAt = snapshot.value?.timestamp ?? 0
  const rowAt = latestRow(rows.value)?.timestamp ?? 0
  if (!snapshotAt && !rowAt) return ''
  return snapshotAt >= rowAt ? '当前值来自最近动作快照' : '当前值来自最近一次上报'
})

const comparisonRows = computed(() => compareWithBaseline(view.value.metrics, baseline.value,
  props.context?.catalog?.seriesFeatureCodes ?? [], props.context?.catalog?.lowerIsWorseFeatures ?? [])
  .filter((row) => row.current != null || row.baseline != null))

/** 自行拉取（仅当外框没有给出快照结论时）：顺带取最近一条原始样本，作为“当前值”的兜底来源。 */
async function load() {
  busy.value = true
  error.value = ''
  ownMissing.value = false
  const endTs = Date.now()
  try {
    const [snap, base, series] = await Promise.all([
      getLatestSnapshot(props.point.id),
      getChannelBaseline(props.point.id).catch(() => null),
      getChannelSeries(props.point.id, endTs - 24 * 60 * 60 * 1000, endTs, 'raw').catch(() => null),
    ])
    ownSnapshot.value = snap
    ownBaseline.value = base
    ownRows.value = series?.rows ?? []
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      ownMissing.value = true
      ownSnapshot.value = null
    } else error.value = errorMessage(e, '动作曲线快照加载失败')
  } finally {
    busy.value = false
    draw()
  }
}

// 首次执行、或刚从“等待外框”转为需自行拉取时取数；外框每次轮询刷新（refreshKey 变化）且快照仍由本视图负责时重新拉取
watch([usesShared, waitingShared, () => props.refreshKey ?? 0], ([sharedNow, waiting, key], previous) => {
  if (sharedNow || waiting) return
  const initial = previous === undefined || previous[1]
  const refreshed = previous !== undefined && previous[2] !== key
  if (initial || refreshed) void load()
}, { immediate: true })

/** 实时帧：动作帧整体替换曲线/标注；指标帧只更新读数。外框只透传真正的实时帧（订阅时的快照回放不在此列）。 */
watch(() => props.realtimePayload, (frame) => {
  if (!frame) return
  if (frame.frame_type === 'STROKE_EVENT_FRAME') liveStroke.value = frame
  if (frame.frame_type === 'METRICS_FRAME') liveMetrics.value = frame
})

function draw() {
  if (!chartEl.value) return
  chart = ensureChart(chart, chartEl.value)
  if (!chart) return
  const palette = chartTheme()
  const data = view.value
  const markXs: { value: number | null; label: string; color: string }[] = [
    { value: data.annotations.tripStartMs, label: '分闸起动点', color: palette.orange },
    { value: data.annotations.contactSeparationMs, label: '触头分离点', color: palette.blue },
    { value: data.annotations.brakingMs, label: '缓冲制动点', color: palette.violet },
  ]
  const verticalMarks = markXs
    .filter((mark) => mark.value != null)
    .map((mark) => ({
      xAxis: mark.value as number,
      lineStyle: { color: mark.color, type: 'dashed' as const, width: 1 },
      label: { formatter: mark.label, position: 'insideEndTop' as const, fontSize: 10 },
    }))
  const spanStart = data.annotations.tripStartMs
  const spanEnd = spanStart != null && data.annotations.totalTimeMs != null ? spanStart + data.annotations.totalTimeMs
    : data.coilAxis.length ? data.coilAxis[data.coilAxis.length - 1] : null
  const bracket = spanStart != null && spanEnd != null && spanEnd > spanStart ? [[
    { xAxis: spanStart }, { xAxis: spanEnd },
  ]] : []

  chart.setOption({
    tooltip: { trigger: 'axis', ...palette.tooltip,
      formatter: (params: unknown) => {
        const items = (Array.isArray(params) ? params : []) as { seriesName?: string; value?: unknown; axisValue?: number | string }[]
        const head = items[0]?.axisValue
        // 坐标轴为真实时间（ms）时显示时间；采样率未知时横轴只是采样点序号，不能标成时间
        const lines = [data.timeUnit === 'ms' ? `时间 ${formatNumber(typeof head === 'number' ? head : Number(head), 1)} ms` : `采样点 ${head ?? '—'}`]
        for (const item of items) {
          if (item.value == null) continue
          const value = Array.isArray(item.value) ? item.value[1] : item.value
          lines.push(`${item.seriesName ?? ''}  ${formatNumber(typeof value === 'number' ? value : Number.NaN, 3)} ${item.seriesName === '机械行程' ? data.travelUnit : data.coilUnit}`)
        }
        return lines.join('\n')
      } },
    legend: { top: 0, textStyle: { color: palette.label } },
    grid: { left: 56, right: 60, top: 40, bottom: 32 },
    xAxis: { type: 'value', name: data.timeUnit, nameTextStyle: { color: palette.axis },
      axisLabel: { color: palette.axis }, axisLine: { lineStyle: { color: palette.split } } },
    yAxis: [
      { type: 'value', name: `线圈电流 (${data.coilUnit})`, scale: true, nameTextStyle: { color: palette.axis }, axisLabel: { color: palette.axis }, splitLine: { lineStyle: { color: palette.split } } },
      { type: 'value', name: `机械行程 (${data.travelUnit})`, scale: true, nameTextStyle: { color: palette.axis }, axisLabel: { color: palette.axis }, splitLine: { show: false } },
    ],
    series: [
      {
        name: '线圈电流', type: 'line', showSymbol: false, animation: false,
        data: data.coil.map((value, index) => [data.coilAxis[index] ?? index, value]),
        lineStyle: { color: palette.feature, width: 1.5 }, itemStyle: { color: palette.feature },
        markLine: verticalMarks.length ? { symbol: 'none', silent: true, data: verticalMarks } : undefined,
        markArea: bracket.length ? {
          silent: true, itemStyle: { color: palette.band },
          label: { show: true, position: 'insideTop', fontSize: 10,
            formatter: `动作总时差 ${formatNumber(data.annotations.totalTimeMs, 1)} ms` },
          data: bracket,
        } : undefined,
      },
      {
        name: '机械行程', type: 'line', yAxisIndex: 1, showSymbol: false, animation: false,
        data: data.travel.map((value, index) => [data.travelAxis[index] ?? index, value]),
        lineStyle: { color: palette.hi, width: 1.5 }, itemStyle: { color: palette.hi },
      },
    ],
  }, { notMerge: true })
}

function resize() { chart?.resize() }
// flush: 'post'——快照到达时图表容器才由 v-else 渲染，等 DOM 更新后再初始化图表
watch([view, theme], draw, { flush: 'post' })
onMounted(() => {
  draw()
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  chart?.dispose()
  window.removeEventListener('resize', resize)
})
</script>

<template>
  <div class="stroke-workbench">
    <section class="panel chart-panel">
      <div class="panel-heading">
        <div><h3>机械特性曲线 · 线圈电流 × 行程</h3><p>同一时间轴上的双 Y 轴同步曲线；虚线标注分闸起动 / 触头分离 / 缓冲制动，色带括出动作总时差</p></div>
        <div class="stroke-tags">
          <span v-if="health" :class="['status-tag', health.tone]"><i></i>HI {{ formatNumber(view.healthIndex, 1) }} · {{ health.label }}</span>
          <span v-if="liveStroke" class="status-tag green"><i></i>实时动作帧已并入</span>
          <span v-else-if="busy" class="status-tag amber"><i></i>加载中…</span>
        </div>
      </div>
      <div v-if="error" class="notice error-notice">{{ error }}</div>
      <div v-else-if="loading" class="empty-cell">正在加载动作曲线…</div>
      <div v-else-if="!hasCurve" class="empty-state"><div>⌁</div><b>{{ missing ? '尚无动作曲线快照' : '该快照未包含曲线数据' }}</b><p>{{ missing ? '断路器完成一次分/合闸动作后，这里会展示线圈电流与触头行程的同步曲线。' : '最新快照只有特征读数，没有线圈电流 / 行程曲线；下一次带曲线的动作上报后自动展示。' }}</p></div>
      <div v-else ref="chartEl" class="stroke-chart"></div>
    </section>

    <section v-if="hasCurve" class="panel chart-panel">
      <div class="panel-heading"><div><h3>动作标注读数</h3><p>由波形自动识别的机械特性时间与物理量</p></div><span class="muted">{{ view.sampledAtLabel }}<template v-if="view.sampledAtRaw != null"> · {{ timeAgo(view.sampledAtRaw) }}</template></span></div>
      <dl class="annotation-grid">
        <div v-for="key in annotationKeys" :key="key">
          <dt>{{ annotationLabels[key] }}</dt>
          <dd>{{ formatNumber(view.annotations[key], 3) }} <em>{{ view.annotations[key] != null ? annotationUnits[key] : '' }}</em></dd>
        </div>
      </dl>
      <p v-if="view.annotationsMissing" class="muted annotation-hint">该{{ liveStroke ? '实时帧' : '快照' }}未携带自动标注（分闸起动 / 触头分离 / 缓冲制动等由分析后写入），曲线本身可正常查看。</p>
      <dl class="snapshot-meta">
        <div><dt>触发类型</dt><dd>{{ view.triggerType || '—' }}</dd></div>
        <div><dt>采样率</dt><dd>{{ view.samplingRate ? `${view.samplingRate} Hz` : '—' }}</dd></div>
        <div><dt>曲线点数</dt><dd>{{ view.pointsCount ?? '—' }}</dd></div>
        <div><dt>时间轴</dt><dd>{{ view.timeUnit }}</dd></div>
        <div><dt>工程单位</dt><dd>{{ snapshot?.engineeringUnit || '—' }}</dd></div>
        <div class="storage-cell"><dt>存储后端 / 快照地址</dt><dd class="subtle-code">{{ view.storage }}</dd></div>
      </dl>
    </section>

    <section class="panel table-panel">
      <div class="panel-heading">
        <div><h3>当前动作 vs 基线对照</h3><p>最新动作特征与通道自学习 P50 基线的相对偏离（k = 当前 / 基线）<template v-if="metricsNote"> · {{ metricsNote }}</template></p></div>
        <span v-if="baseline" :class="['status-tag', baselineState(baseline.status).tone]"><i></i>基线 {{ baselineState(baseline.status).label }} · {{ baseline.learnedSamples }}/{{ baseline.minimumSamples }} 样本</span>
        <span v-else class="status-tag gray"><i></i>{{ baselineState().label }}</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>特征</th><th>当前值</th><th>基线 P50</th><th>比值 k</th><th>变异系数 CV</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="row in comparisonRows" :key="row.feature">
              <td><b>{{ row.label }}</b><small class="subtle-code">{{ row.feature }}</small></td>
              <td>{{ formatNumber(row.current, 3) }} {{ row.unit }}</td>
              <td>{{ formatNumber(row.baseline, 3) }}</td>
              <td :class="['k-cell', 'tone-text', row.tone]" :title="ratioHint(row)">{{ formatRatio(row.ratio) }}</td>
              <td>{{ formatNumber(row.cv, 3) }}</td>
              <td><span :class="['status-tag', row.status ? baselineState(row.status).tone : 'gray']"><i></i>{{ row.status ? baselineState(row.status).label : '无基线' }}</span></td>
            </tr>
            <tr v-if="!comparisonRows.length"><td colspan="6" class="empty-cell">{{ loading ? '正在加载…' : '暂无可对照的动作特征' }}</td></tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.stroke-workbench { display: grid; gap: 14px; }
.stroke-tags { display: flex; gap: 6px; flex-wrap: wrap; }
.stroke-chart { height: 340px; width: 100%; }
.annotation-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 10px; margin: 14px 0 0; }
.annotation-grid dt { color: var(--muted); font-size: 10px; }
.annotation-grid dd { margin: 5px 0 0; font-size: 15px; }
.annotation-grid dd em { font-style: normal; color: var(--muted); font-size: 10px; margin-left: 3px; }
.annotation-hint { margin: 12px 0 0; line-height: 1.6; }
.snapshot-meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 10px; margin: 14px 0 0; padding-top: 12px; border-top: 1px solid var(--line); }
.snapshot-meta dt { color: var(--muted); font-size: 10px; }
.snapshot-meta dd { margin: 4px 0 0; font-size: 12px; overflow-wrap: anywhere; }
.k-cell { font-weight: 600; }
</style>
