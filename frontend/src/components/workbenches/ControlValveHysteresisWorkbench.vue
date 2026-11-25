<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { EChartsType } from '@/charts'
import { ApiError } from '@/api/http'
import { getChannelBaseline, getChannelSeries, getLatestSnapshot, getValveLoop } from '@/api/pdm'
import type { ChannelBaseline, ChannelSeriesRow, ChannelSnapshot, MetricsFrame, ValveLoop, ValveLoopFrame } from '@/api/types'
import { useTheme } from '@/composables/ui'
import { baselineState } from '@/telemetry-status'
import { compareWithBaseline, currentMetrics as pickCurrentMetrics, featureTitle, formatNumber, formatRatio, formatTime, healthLevel, latestHealthIndex, latestRow, timeAgo } from '@/modality'
import { chartTheme, ensureChart, errorMessage, numericFields, ratioHint, type WorkbenchProps } from './shared'

/**
 * 控制调节阀专家视图（设计文档 §5 情况 C）：
 * 控制信号 CV vs 反馈位移 FB 迟滞回环（Lissajous Loop）+ 动态死区/迟滞定量展示；
 * 高分辨率动作回环快照与实时回环帧叠加更新。
 */
const props = defineProps<WorkbenchProps>()
const { theme } = useTheme()
const loopChartEl = ref<HTMLDivElement>()
const snapChartEl = ref<HTMLDivElement>()
let loopChart: EChartsType | undefined
let snapChart: EChartsType | undefined

const loop = ref<ValveLoop | null>(null)
const snapshot = ref<ChannelSnapshot | null>(null)
// 基线与时序由外框统一拉取（shared）时直接复用；独立挂载时才由本视图自行取数
const ownBaseline = ref<ChannelBaseline | null>(null)
const ownRows = ref<ChannelSeriesRow[]>([])
const error = ref('')
const busy = ref(false)
const liveLoop = ref<ValveLoopFrame | null>(null)
const liveMetrics = ref<MetricsFrame | null>(null)

const baseline = computed(() => (props.shared ? props.shared.baseline : ownBaseline.value))
const rows = computed(() => (props.shared ? props.shared.rows : ownRows.value))

const analysisKeys = ['deadbandEstimatePct', 'loopAreaPct2', 'hysteresisPct', 'maxDeviationPct', 'reversalCount'] as const
const analysisLabels: Record<string, string> = {
  deadbandEstimatePct: '回环死区估计',
  loopAreaPct2: '回环面积',
  hysteresisPct: '迟滞',
  maxDeviationPct: '最大偏差',
  reversalCount: '换向次数',
}
const analysisUnits: Record<string, string> = { deadbandEstimatePct: '%', loopAreaPct2: '%²', hysteresisPct: '%', maxDeviationPct: '%', reversalCount: '次' }

const featureOrder = ['hysteresis_deadband_pct', 'travel_time_lag_ms', 'friction_force_kn', 'ultrasonic_leak_rms']

/** 回环点对：实时回环帧 > 快照级 cv_pct/fb_pct 曲线 > REST 回环接口采样点。 */
function pairsFromCurves(curves: Record<string, number[]>): [number, number][] {
  const cv = curves.cv_pct ?? []
  const fb = curves.fb_pct ?? []
  const count = Math.min(cv.length, fb.length)
  const pairs: [number, number][] = []
  for (let i = 0; i < count; i++) pairs.push([cv[i], fb[i]])
  return pairs
}

const loopPairs = computed<[number, number][]>(() => (loop.value?.points ?? []).map((point) => [point.cv, point.fb]))
const snapPairs = computed<[number, number][]>(() => {
  if (liveLoop.value) return pairsFromCurves(liveLoop.value.curves ?? {})
  const curves = snapshot.value?.curves
  if (curves && (curves.cv_pct?.length ?? 0) > 1 && (curves.fb_pct?.length ?? 0) > 1) return pairsFromCurves(curves)
  return []
})
const liveAnalysis = computed(() => numericFields(liveLoop.value?.annotations, analysisKeys))
/** 量化分析取值：实时帧带标注则用实时值；否则仅在 REST 回环状态为 OK 时使用其分析——NO_DATA/不支持时后端返回全 0，不能显示成 “0 %”。 */
const analysis = computed<Record<string, number | null> | null>(() => {
  if (liveLoop.value) return liveAnalysis.value
  return loop.value?.status === 'OK' ? (loop.value.analysis as unknown as Record<string, number | null>) : null
})
const liveAnnotationsMissing = computed(() => !!liveLoop.value && Object.keys(liveLoop.value.annotations ?? {}).length === 0)

const currentRow = computed(() => latestRow(rows.value))
// “当前值”：实时帧 > 快照 / 最近时序行中较新者（没有实时帧时不再整列为空）
const currentMetrics = computed(() => pickCurrentMetrics({ frame: liveLoop.value ?? liveMetrics.value, snapshot: snapshot.value, rows: rows.value }))
const comparisonRows = computed(() => compareWithBaseline(currentMetrics.value, baseline.value,
  [...featureOrder, ...(props.context?.catalog?.seriesFeatureCodes ?? [])], props.context?.catalog?.lowerIsWorseFeatures ?? [])
  .filter((row) => row.current != null || row.baseline != null))
const metricsNote = computed(() => {
  if (liveLoop.value || liveMetrics.value) return '当前值来自实时帧'
  const snapshotAt = snapshot.value?.timestamp ?? 0
  const rowAt = currentRow.value?.timestamp ?? 0
  if (!snapshotAt && !rowAt) return ''
  return snapshotAt >= rowAt ? '当前值来自最近动作快照' : '当前值来自最近一次上报'
})
const currentHealth = computed(() => liveLoop.value?.health_index ?? snapshot.value?.healthIndex ?? latestHealthIndex(rows.value) ?? null)
const health = computed(() => healthLevel(currentHealth.value))
const baselineMeta = computed(() => baselineState(baseline.value?.status))

/** 坐标范围：数据外扩 5%，至少留 2% 展示 45° 参考线。 */
function extent(pairs: [number, number][]) {
  let min = Infinity
  let max = -Infinity
  for (const [x, y] of pairs) { min = Math.min(min, x, y); max = Math.max(max, x, y) }
  if (!Number.isFinite(min) || !Number.isFinite(max)) return [0, 100]
  const span = Math.max(max - min, 1)
  return [Math.max(0, min - span * 0.05), max + span * 0.05]
}

function loopOption(pairs: [number, number][], seriesName: string) {
  const palette = chartTheme()
  const [min, max] = extent(pairs)
  return {
    tooltip: { trigger: 'item', ...palette.tooltip,
      formatter: (params: { seriesName?: string; value?: [number, number] }) =>
        params.value ? `${seriesName}\nCV 指令开度 ${formatNumber(params.value[0], 2)} %\nFB 反馈开度 ${formatNumber(params.value[1], 2)} %` : '' },
    grid: { left: 56, right: 24, top: 30, bottom: 36 },
    xAxis: { type: 'value', name: 'CV 指令开度 (%)', min, max, nameTextStyle: { color: palette.axis },
      axisLabel: { color: palette.axis }, axisLine: { lineStyle: { color: palette.split } }, splitLine: { lineStyle: { color: palette.split } } },
    yAxis: { type: 'value', name: 'FB 反馈开度 (%)', min, max, nameTextStyle: { color: palette.axis },
      axisLabel: { color: palette.axis }, axisLine: { lineStyle: { color: palette.split } }, splitLine: { lineStyle: { color: palette.split } } },
    series: [{
      name: seriesName, type: 'line', showSymbol: pairs.length <= 60, animation: false,
      data: pairs, lineStyle: { color: palette.feature, width: 1.5 }, itemStyle: { color: palette.feature },
      markLine: {
        symbol: 'none', silent: true,
        lineStyle: { color: palette.muted, type: 'dashed' as const, width: 1 },
        label: { formatter: 'FB = CV', fontSize: 10, position: 'end' as const },
        data: [[{ coord: [min, min] as [number, number] }, { coord: [max, max] as [number, number] }]],
      },
    }],
  }
}

function draw() {
  // 回环图容器只在有点可画时可见（v-show）：隐藏（尺寸为 0）时不初始化也不绘制，避免图表绑定到 0×0 画布后不再显示。
  // 本函数必须在 DOM 更新完成后调用（见下方 watch），否则量到的宽度还没有反映 v-show 的结果。
  if (loopChartEl.value && loopPairs.value.length && loopChartEl.value.offsetWidth > 0) {
    loopChart = ensureChart(loopChart, loopChartEl.value)
    loopChart.setOption(loopOption(loopPairs.value, '回环采样'), { notMerge: true })
  }
  if (snapChartEl.value) {
    snapChart = ensureChart(snapChart, snapChartEl.value)
    if (snapPairs.value.length) snapChart.setOption(loopOption(snapPairs.value, liveLoop.value ? '实时回环帧' : '快照回环'), { notMerge: true })
  }
}

/** 窗口尺寸变化：已有图表跟随缩放；此前因容器隐藏而未初始化的图，在容器有了尺寸后补画。 */
function resize() {
  loopChart?.resize()
  snapChart?.resize()
  draw()
}

/** 拉取回环 / 快照；基线与时序在外框已取时直接复用，独立挂载时才自行取（时序只取最近一段原始样本，供“当前值”兜底）。 */
async function load() {
  busy.value = true
  error.value = ''
  const now = Date.now()
  try {
    const [loopData, snap, base, series] = await Promise.all([
      getValveLoop(props.point.id, 'PT1H'),
      getLatestSnapshot(props.point.id).catch((e) => (e instanceof ApiError && e.status === 404 ? null : Promise.reject(e))),
      props.shared ? Promise.resolve(props.shared.baseline) : getChannelBaseline(props.point.id).catch(() => null),
      props.shared ? Promise.resolve(props.shared.rows) : getChannelSeries(props.point.id, now - 15 * 60 * 1000, now, 'raw').then((data) => data.rows).catch(() => []),
    ])
    loop.value = loopData
    snapshot.value = snap
    ownBaseline.value = base
    ownRows.value = series
  } catch (e) {
    error.value = errorMessage(e, '迟滞回环数据加载失败')
  } finally {
    busy.value = false
  }
}

/** 实时帧：回环帧整体替换回环/标注；指标帧只更新对照表的当前值。外框只透传真正的实时帧（订阅时的快照回放不在此列）。 */
watch(() => props.realtimePayload, (frame) => {
  if (!frame) return
  if (frame.frame_type === 'VALVE_LOOP_FRAME') liveLoop.value = frame
  if (frame.frame_type === 'METRICS_FRAME') liveMetrics.value = frame
})

// 轮询兜底（实时流不可用）时外框递增 refreshKey：回环与快照由本视图负责，需要重新拉取
watch(() => props.refreshKey, (key, previous) => {
  if (key !== previous) void load()
})

// 数据 / 主题变化后，等本轮 DOM 更新彻底完成再绘制。不能直接放在 flush:'post' 的 watcher 里：
// v-show 是在渲染期间才排入 post 队列的 updated 指令钩子里改 display 的，排得比这个 watcher 晚，
// 在 watcher 里量容器宽度会读到 0（仍是 display:none），回环图就永远不会被初始化。
watch([loopPairs, snapPairs, theme], () => { void nextTick(draw) })
onMounted(() => {
  window.addEventListener('resize', resize)
  void load()
})
onBeforeUnmount(() => {
  loopChart?.dispose()
  snapChart?.dispose()
  window.removeEventListener('resize', resize)
})
</script>

<template>
  <div class="valve-workbench">
    <section class="content-grid valve-grid">
      <section class="panel chart-panel">
        <div class="panel-heading">
          <div><h3>迟滞回环 CV → FB（最近 1 小时）</h3><p>给定控制信号指令开度与反馈位移构成的 Lissajous 回环；虚线为 45° 理想参考线（FB = CV）</p></div>
          <span v-if="health" :class="['status-tag', health.tone]"><i></i>HI {{ formatNumber(currentHealth, 1) }} · {{ health.label }}</span>
        </div>
        <!-- 判定顺序：错误 → 模态不支持 → 加载中 → 无数据；加载期间 loop 为空，不能先落到“无数据”分支 -->
        <div v-if="error" class="notice error-notice">{{ error }}</div>
        <div v-else-if="loop?.status === 'UNSUPPORTED_MODALITY'" class="notice info-notice">当前通道模态不支持 CV/FB 迟滞回环分析，请确认测点绑定的机理插件；下方仍展示最新特征与基线对照。</div>
        <div v-else-if="busy && !loop" class="muted" role="status">正在加载回环数据…</div>
        <div v-else-if="loop?.status === 'NO_DATA' || !loop?.points?.length" class="notice info-notice">近窗口内没有 CV/FB 过程量，请上报可选特征 cv_pct / fb_pct。</div>
        <div v-show="loopPairs.length" ref="loopChartEl" class="loop-chart"></div>
        <p v-if="loop?.status === 'OK' && loop.points.length" class="muted loop-meta">
          窗口 {{ loop.window }} · {{ loop.sampleCount }} 个采样点 · {{ formatTime(loop.from) }} ~ {{ formatTime(loop.to) }}
          <template v-if="loop.truncated"><br />原始样本达到上限（{{ loop.limit ?? '—' }}），回环统计基于窗口内最近的采样。</template>
        </p>
      </section>

      <section class="panel chart-panel">
        <div class="panel-heading">
          <div><h3>量化分析</h3><p>回环死区、迟滞与偏差的定量结果{{ liveLoop ? '（已并入实时回环帧）' : '' }}</p></div>
          <span v-if="liveLoop" class="status-tag green"><i></i>实时回环帧</span>
        </div>
        <dl class="analysis-grid">
          <div v-for="key in analysisKeys" :key="key">
            <dt>{{ analysisLabels[key] }}</dt>
            <dd>{{ formatNumber(analysis?.[key] ?? null, 3) }} <em>{{ analysis?.[key] != null ? analysisUnits[key] : '' }}</em></dd>
          </div>
        </dl>
        <p v-if="liveAnnotationsMissing" class="muted analysis-hint">实时回环帧未携带量化分析结果（annotations 为空），回环曲线可正常查看。</p>
        <div class="baseline-line">
          <span :class="['status-tag', baselineMeta.tone]"><i></i>基线 {{ baselineMeta.label }}</span>
          <span v-if="baseline" class="muted">{{ baseline.learnedSamples }}/{{ baseline.minimumSamples }} 样本 · 通道 {{ baseline.channelCode }}<template v-if="baseline.lockedAt"> · 锁定于 {{ formatTime(baseline.lockedAt) }}</template></span>
        </div>
        <p v-if="baseline?.warning" class="warning-note">{{ baseline.warning }}</p>
      </section>
    </section>

    <section v-if="snapPairs.length" class="panel chart-panel">
      <div class="panel-heading"><div><h3>快照级回环捕获</h3><p>最新一次动作快照的高分辨率 CV/FB 回环{{ liveLoop ? '（已被实时回环帧替换）' : '' }}</p></div><span class="muted">{{ liveLoop ? '实时流' : snapshot?.triggerType || '—' }} · {{ formatTime(liveLoop?.timestamp ?? snapshot?.timestamp ?? null) }}</span></div>
      <div ref="snapChartEl" class="loop-chart"></div>
    </section>

    <section class="panel table-panel">
      <div class="panel-heading">
        <div><h3>当前特征 vs 基线对照</h3><p>最新上报的阀路特征与通道自学习 P50 基线的相对偏离（k = 当前 / 基线）<template v-if="metricsNote"> · {{ metricsNote }}</template></p></div>
        <span class="muted">{{ formatTime(liveLoop?.timestamp ?? snapshot?.timestamp ?? currentRow?.timestamp ?? null) }}<template v-if="snapshot?.timestamp && !liveLoop"> · {{ timeAgo(snapshot.timestamp) }}</template></span>
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
            <tr v-if="!comparisonRows.length"><td colspan="6" class="empty-cell">{{ busy ? '正在加载…' : '暂无可对照的阀路特征' }}</td></tr>
          </tbody>
        </table>
      </div>
      <p class="muted table-foot">特征含义：{{ featureTitle('hysteresis_deadband_pct') }} · {{ featureTitle('travel_time_lag_ms') }} · {{ featureTitle('friction_force_kn') }} · {{ featureTitle('ultrasonic_leak_rms') }}</p>
    </section>
  </div>
</template>

<style scoped>
.valve-workbench { display: grid; gap: 14px; }
.valve-grid { grid-template-columns: minmax(0, 1.4fr) minmax(280px, 1fr); align-items: start; }
.loop-chart { height: 320px; width: 100%; margin-top: 10px; }
.loop-meta { margin: 8px 0 0; line-height: 1.6; }
.analysis-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 10px; margin: 12px 0 0; }
.analysis-grid dt { color: var(--muted); font-size: 10px; }
.analysis-grid dd { margin: 5px 0 0; font-size: 16px; }
.analysis-grid dd em { font-style: normal; color: var(--muted); font-size: 10px; margin-left: 3px; }
.analysis-hint { margin: 10px 0 0; line-height: 1.6; }
.baseline-line { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--line); }
.table-foot { margin: 10px 0 12px; }
.k-cell { font-weight: 600; }
@media (max-width: 1000px) { .valve-grid { grid-template-columns: 1fr; } }
</style>
