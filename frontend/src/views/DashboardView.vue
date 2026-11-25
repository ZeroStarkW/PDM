<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import type { EChartsType } from '@/charts'
import { getChannelSeries, getOverview, getTelemetry, loadModalityCatalog } from '@/api/pdm'
import type { ChannelSeriesRow, ModalityCatalog, Overview, Telemetry } from '@/api/types'
import { useTheme } from '@/composables/ui'
import { defaultTrendFeature, featureTitle, modalityName } from '@/modality'
import ChannelTrendChart from '@/components/workbenches/ChannelTrendChart.vue'
import { ensureChart } from '@/components/workbenches/shared'
import { pointStatusLabel, statusTone, telemetryTooltip } from '@/telemetry-status'

const overview = ref<Overview | null>(null)
const { theme } = useTheme()
const selectedPoint = ref<number | null>(null)
const selectedRange = ref('24h')
const history = ref<Telemetry[]>([])
const seriesRows = ref<ChannelSeriesRow[]>([])
const seriesFeatures = ref<string[]>([])
const seriesLoading = ref(false)
const error = ref('')
/** 通道时序加载错误：与总览错误分开保存，避免被并发成功的总览请求清掉。 */
const historyError = ref('')
const chartEl = ref<HTMLDivElement>()
const catalogs = ref<ModalityCatalog[]>([])
let chart: EChartsType | undefined
let timer: number | undefined
let overviewRequest: AbortController | undefined
let historyRequest: AbortController | undefined

/**
 * 多模态分流：非旋转振动测点的证据趋势改走 v2 通道时序，并复用统一趋势图 ChannelTrendChart
 *（真实时间轴 + 双 Y 轴，而不是把一天内的时间点当成分类轴）。
 */
const selectedPointModality = computed(() => overview.value?.points.find((point) => point.pointId === selectedPoint.value)?.modalityType ?? null)
const isNonVib = computed(() => !!selectedPointModality.value && selectedPointModality.value !== 'ROTATING_VIB')
/** 趋势特征：共享默认特征（目录 trendFeatureCode → 模态兜底 → 主特征 …），不再直接取字典序第一个指标。 */
const trendFeature = computed(() => defaultTrendFeature(selectedPointModality.value,
  catalogs.value.find((item) => item.modalityType === selectedPointModality.value) ?? null, seriesFeatures.value))

async function refreshOverview() {
  if (overviewRequest) return
  const request = new AbortController()
  overviewRequest = request
  try {
    const data = await getOverview(request.signal)
    if (request.signal.aborted) return
    overview.value = data
    error.value = ''
    if (!data.points.some((point) => point.pointId === selectedPoint.value)) {
      selectedPoint.value = data.points[0]?.pointId ?? null
    }
  } catch (e) {
    if (!request.signal.aborted) error.value = e instanceof Error ? e.message : '总览数据暂不可用'
  } finally {
    overviewRequest = undefined
  }
}

async function loadHistory() {
  const pointId = selectedPoint.value
  const rangeValue = selectedRange.value
  if (historyRequest || pointId == null) return
  const request = new AbortController()
  historyRequest = request
  const range = rangeOptions.find((option) => option.value === rangeValue) || rangeOptions[1]
  const to = new Date()
  const from = new Date(to.getTime() - range.milliseconds)
  const isCurrent = () => !request.signal.aborted && pointId === selectedPoint.value && rangeValue === selectedRange.value
  seriesLoading.value = true
  if (isNonVib.value) {
    try {
      const series = await getChannelSeries(pointId, from.getTime(), to.getTime(), range.interval, request.signal)
      if (isCurrent()) { seriesRows.value = series.rows; seriesFeatures.value = series.features; historyError.value = '' }
    } catch (e) {
      // 加载失败要让人看见（此前静默清空，只剩一张空图）；独立于总览的错误状态，避免被并发成功请求清掉
      if (isCurrent()) {
        seriesRows.value = []
        seriesFeatures.value = []
        historyError.value = e instanceof Error && e.message ? `测点趋势加载失败：${e.message}` : '测点趋势加载失败'
      }
    } finally {
      seriesLoading.value = false
      if (historyRequest === request) historyRequest = undefined
    }
    return
  }
  try {
    const data = await getTelemetry(pointId, from.toISOString(), to.toISOString(), range.interval, request.signal)
    if (isCurrent()) history.value = data
  } catch {
    if (isCurrent()) history.value = []
  } finally {
    seriesLoading.value = false
    if (historyRequest === request) historyRequest = undefined
  }
}

function refresh() {
  void refreshOverview()
  void loadHistory()
}

function draw() {
  // 非振动测点由 ChannelTrendChart 绘制：丢弃可能已有的 v1 图实例（其容器已随 v-else 卸载）
  if (isNonVib.value) {
    chart?.dispose()
    chart = undefined
    return
  }
  // 总览未返回前测点模态未知：不初始化，避免为随后的非振动测点建出一个立刻被弃用的图实例
  if (!overview.value) return
  chart = ensureChart(chart, chartEl.value)
  if (!chart) return
  const light = theme.value === 'light'
  chart.setOption({
    color: [light ? '#138b7c' : '#4dd6be', light ? '#bd7a16' : '#f2b35d'],
    tooltip: {
      trigger: 'axis',
      renderMode: 'richText',
      backgroundColor: light ? '#ffffff' : '#162538',
      borderColor: light ? '#dce4eb' : '#26384d',
      textStyle: { color: light ? '#233247' : '#dce7f4' },
      formatter: (params: unknown) => telemetryTooltip(params, history.value),
    },
    grid: { left: 42, right: 22, top: 28, bottom: 28 },
    xAxis: { type: 'category', boundaryGap: false, data: history.value.map((p) => new Date(p.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })), axisLabel: { color: light ? '#718096' : '#71839a' }, axisLine: { lineStyle: { color: light ? '#dce4eb' : '#26384d' } } },
    yAxis: [{ type: 'value', name: 'mm/s', min: 0, axisLabel: { color: light ? '#718096' : '#71839a' }, splitLine: { lineStyle: { color: light ? '#e8edf1' : '#1e2d40' } } }, { type: 'value', name: 'HI', min: 0, max: 100, axisLabel: { color: light ? '#718096' : '#71839a' }, splitLine: { show: false } }],
    series: [
      { name: '振动速度 RMS', type: 'line', smooth: true, showSymbol: false, data: history.value.map((p) => p.vRms), areaStyle: { opacity: 0.08 } },
      { name: '健康指数 HI', type: 'line', yAxisIndex: 1, smooth: true, showSymbol: false, data: history.value.map((p) => p.healthIndex ?? null), lineStyle: { color: light ? '#bd7a16' : '#f2b35d' } },
    ],
  })
}
// 模态解析结果也触发重绘：切到非振动测点时释放 v1 图实例，切回振动测点时容器已重建、需重新绑定
watch([history, seriesRows, theme, isNonVib], draw)
watch([selectedPoint, selectedRange], () => {
  historyRequest?.abort()
  historyRequest = undefined
  history.value = []
  seriesRows.value = []
  seriesFeatures.value = []
  historyError.value = ''
  void loadHistory()
})
onMounted(() => {
  draw()
  refresh()
  // 模态目录只用于挑选趋势默认特征；失败时按模态静态兜底，不影响总览
  void loadModalityCatalog().then((items) => { catalogs.value = items })
  timer = window.setInterval(refresh, 15000)
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  if (timer != null) window.clearInterval(timer)
  overviewRequest?.abort()
  historyRequest?.abort()
  chart?.dispose()
  window.removeEventListener('resize', resize)
})
function resize() { chart?.resize() }
const rangeOptions = [
  { value: '1h', label: '最近 1 小时', milliseconds: 60 * 60 * 1000, interval: '1m' },
  { value: '24h', label: '最近 24 小时', milliseconds: 24 * 60 * 60 * 1000, interval: '5m' },
  { value: '7d', label: '最近 7 天', milliseconds: 7 * 24 * 60 * 60 * 1000, interval: '1h' },
  { value: '30d', label: '最近 30 天', milliseconds: 30 * 24 * 60 * 60 * 1000, interval: '6h' },
]
</script>

<template>
  <div class="page-stack">
    <div class="page-intro"><div><h2>设备健康一览</h2><p>相对基线评估振动、断路器、调节阀等多模态设备的运行状态与劣化趋势。</p></div><div class="chart-filters"><RouterLink class="quiet-button" :to="selectedPoint ? `/workbench?pointId=${selectedPoint}` : '/workbench'">多模态诊断工作站 →</RouterLink><button class="quiet-button" @click="refresh">↻ 刷新数据</button></div></div>
    <div v-if="error" class="notice error-notice">{{ error }}</div>
    <section class="metric-grid">
      <article class="metric-card"><div class="metric-top"><span>纳管工厂</span><b class="metric-icon teal">⌂</b></div><strong>{{ overview?.factoryCount ?? '—' }}</strong><small>工厂 / 产线范围</small></article>
      <article class="metric-card"><div class="metric-top"><span>设备健康分析</span><b class="metric-icon blue">▦</b></div><strong>{{ overview?.deviceCount ?? '—' }}</strong><small>{{ overview?.pointCount ?? 0 }} 个测点提供证据</small></article>
      <article class="metric-card"><div class="metric-top"><span>关注与告警</span><b class="metric-icon amber">◉</b></div><strong>{{ (overview?.attentionCount ?? 0) + (overview?.warningCount ?? 0) + (overview?.dangerCount ?? 0) }}</strong><small>{{ overview?.activeAlertCount ?? 0 }} 条待处理告警</small></article>
      <article class="metric-card"><div class="metric-top"><span>待办工单</span><b class="metric-icon violet">✓</b></div><strong>{{ overview?.openWorkOrderCount ?? 0 }}</strong><small>需要跟进的维护事项</small></article>
    </section>
    <section class="content-grid dashboard-grid">
      <article class="panel chart-panel"><div class="panel-heading"><div><h3>设备证据趋势</h3><p>下钻查看设备代表性测点 · {{ modalityName(selectedPointModality) }} · {{ rangeOptions.find(option => option.value === selectedRange)?.label }}</p></div><div class="chart-filters"><select v-model.number="selectedPoint" class="compact-select" aria-label="证据趋势测点"><option v-for="p in overview?.points || []" :key="p.pointId" :value="p.pointId">{{ p.deviceCode }} / {{ p.pointCode }}</option></select><select v-model="selectedRange" class="compact-select" aria-label="趋势时间范围"><option v-for="range in rangeOptions" :key="range.value" :value="range.value">{{ range.label }}</option></select></div></div>
      <!-- 非振动测点复用统一趋势图 ChannelTrendChart（真实时间轴）；振动测点沿用 v RMS + HI 图 -->
      <template v-if="isNonVib">
        <div v-if="historyError" class="notice error-notice" role="alert">{{ historyError }}</div>
        <div v-else-if="!seriesRows.length" class="empty-cell">{{ seriesLoading ? '正在加载通道时序…' : '该测点在所选时间范围内没有上报数据' }}</div>
        <ChannelTrendChart v-else :rows="seriesRows" :feature="trendFeature" :height="247" class="trend-chart" />
      </template>
      <div v-else ref="chartEl" class="trend-chart"></div>
      <div class="legend-row"><span v-if="!isNonVib"><i class="legend-dot teal-dot"></i>振动速度 RMS (mm/s)</span><span v-else><i class="legend-dot teal-dot"></i>{{ trendFeature ? featureTitle(trendFeature) : '通道主特征' }}</span><span><i class="legend-dot amber-dot"></i>测点 HI 证据</span></div><div class="notice info-notice"><b>设备健康口径</b><span>设备 HI 取在线、有效测点中最低的 HI，避免单个高风险位置被平均值掩盖；下方设备清单同时显示测点评估覆盖情况。</span></div></article>
      <article class="panel status-panel"><div class="panel-heading"><div><h3>健康分布</h3><p>按最新有效采样统计</p></div><span class="live-pill"><i></i> 实时</span></div>
        <div class="health-total"><strong>{{ overview?.deviceCount ?? 0 }}</strong><span>监测设备</span></div>
        <div class="distribution">
          <div><span><i class="legend-dot teal-dot"></i>健康设备</span><b>{{ overview?.healthyCount ?? 0 }}</b></div>
          <div><span><i class="legend-dot amber-dot"></i>关注设备</span><b>{{ overview?.attentionCount ?? 0 }}</b></div>
          <div><span><i class="legend-dot orange-dot"></i>警告设备</span><b>{{ overview?.warningCount ?? 0 }}</b></div>
          <div><span><i class="legend-dot red-dot"></i>危险设备</span><b>{{ overview?.dangerCount ?? 0 }}</b></div>
          <div><span><i class="legend-dot gray-dot"></i>暂无有效评分</span><b>{{ overview?.unscoredDeviceCount ?? 0 }}</b></div>
          <div><span><i class="legend-dot orange-dot"></i>传感器异常测点</span><b>{{ overview?.sensorCheckCount ?? 0 }}</b></div>
          <div><span><i class="legend-dot gray-dot"></i>停机测点</span><b>{{ overview?.stoppedCount ?? 0 }}</b></div>
          <div><span><i class="legend-dot blue-dot"></i>学习中设备</span><b>{{ overview?.learningCount ?? 0 }}</b></div>
        </div>
        <div class="distribution freshness-total"><div><span><i class="legend-dot amber-dot"></i>数据过期</span><b>{{ overview?.staleCount ?? 0 }}</b></div></div>
      </article>
    </section>
    <section class="panel table-panel"><div class="panel-heading"><div><h3>设备健康清单</h3><p>设备健康由下属测点评估汇总，风险最高的有效测点作为当前代表分</p></div><RouterLink to="/devices" class="text-link">查看资产 →</RouterLink></div>
      <div class="table-wrap"><table><thead><tr><th>工厂</th><th>设备</th><th>健康指数</th><th>设备状态</th><th>测点覆盖</th><th>主要风险证据</th><th>数据覆盖</th><th>异常测点</th></tr></thead><tbody>
        <tr v-for="device in overview?.devices || []" :key="device.deviceId">
          <td>{{ device.factoryCode }}</td>
          <td><RouterLink :to="`/devices/${device.deviceId}`" class="table-link">{{ device.deviceName }}<small>{{ device.deviceCode }} · {{ device.deviceTypeName }}</small></RouterLink></td>
          <td><b :class="`hi-value ${statusTone(device.status)}`">{{ device.healthIndex == null ? '—' : device.healthIndex.toFixed(1) }}</b></td>
          <td><span :class="['status-tag', statusTone(device.status)]"><i></i>{{ pointStatusLabel(device.status) }}</span></td>
          <td>{{ device.scoredPointCount }} / {{ device.pointCount }} 个有效测点</td>
          <td>{{ device.worstPointCode || '—' }}<small>{{ device.healthIndex == null ? '暂无有效评分' : `代表分 HI ${device.healthIndex.toFixed(1)}` }}</small></td>
          <td><span :class="`status-tag ${device.coverage === 'COMPLETE' ? 'green' : device.coverage === 'PARTIAL' ? 'amber' : 'gray'}`"><i></i>{{ device.coverage === 'COMPLETE' ? '完整' : device.coverage === 'PARTIAL' ? '部分' : '暂无' }}</span></td>
          <td>{{ device.sensorCheckPointCount }} 异常 · {{ device.stalePointCount }} 过期 · {{ device.stoppedPointCount }} 停机</td>
        </tr>
        <tr v-if="!overview?.devices.length"><td colspan="8" class="empty-cell">还没有设备，请先在“资产拓扑”中创建设备与测点。</td></tr>
      </tbody></table></div>
    </section>
  </div>
</template>

<style scoped>
.freshness-total { margin-top: 12px; }
</style>
