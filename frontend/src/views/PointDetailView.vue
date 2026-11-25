<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import type { EChartsType } from '@/charts'
import { getChannelBaseline, getChannelSeries, getOverview, getTelemetry, loadModalityCatalog } from '@/api/pdm'
import type { ChannelBaseline, ChannelSeriesRow, ModalityCatalog, PointHealth, Telemetry } from '@/api/types'
import ChannelTrendChart from '@/components/workbenches/ChannelTrendChart.vue'
import { ensureChart } from '@/components/workbenches/shared'
import { useTheme } from '@/composables/ui'
import { defaultTrendFeature, featureTitle, formatNumber, formatTime, freshnessRule, isEventChannel, modalityName, staleAfterSeconds } from '@/modality'
import { baselineState, fixed, freshnessState, pointStatusLabel, qualityLabel, telemetryTime, telemetryTooltip } from '@/telemetry-status'

const route = useRoute()
const { theme } = useTheme()
const pointId = computed(() => Number(route.params.id))
const point = ref<PointHealth | null>(null)
const samples = ref<Telemetry[]>([])
const selectedRange = ref('24h')
const chartEl = ref<HTMLDivElement>()
const error = ref('')
let chart: EChartsType | undefined
let pending: AbortController | undefined

// ==== 多模态分流：先解析测点模态，再决定分支 ====
// 振动测点沿用 v1 遥测渲染；其余模态走 v2 通道时序 + 通道基线。非振动测点绝不调用振动专用的 v1 遥测接口
//（未部署振动列的环境上该接口会 500，若先探测再分流，v2 分支永远走不到）。
const isVib = computed(() => !point.value?.modalityType || point.value.modalityType === 'ROTATING_VIB')
const seriesRows = ref<ChannelSeriesRow[]>([])
const seriesFeatures = ref<string[]>([])
const channelBaseline = ref<ChannelBaseline | null>(null)
const baselineLoad = ref<'LOADING' | 'READY' | 'FAILED'>('LOADING')
const trendFeature = ref('')
const catalogs = ref<ModalityCatalog[]>([])
const catalog = computed(() => catalogs.value.find((item) => item.modalityType === point.value?.modalityType) ?? null)
const baselineMeta = computed(() => baselineState(channelBaseline.value?.status))
const vBaselineMeta = computed(() => baselineState(point.value?.baselineStatus === 1 ? 'ACTIVE' : 'LEARNING'))
/** 事件型（动作触发）通道：目录 cadence 优先，目录缺失时按模态兜底。 */
const isEvent = computed(() => isEventChannel(point.value?.modalityType, catalog.value))
const staleAfter = computed(() => staleAfterSeconds(catalog.value?.cadence ?? (isEvent.value ? 'EVENT' : null), catalog.value))
/** 新鲜度口径说明：事件型通道动作稀疏，“在线”按长时限判定，需要向用户说明。 */
const freshnessNote = computed(() => (isEvent.value || point.value?.freshness === 'STALE' ? freshnessRule(catalog.value?.cadence ?? (isEvent.value ? 'EVENT' : null), staleAfter.value) : ''))
let trendFeatureTouched = false

/** 趋势特征：共享默认特征（目录 trendFeatureCode → 模态兜底 → 主特征 …）；用户手动选过且仍可用时保留。 */
function syncTrendFeature() {
  if (trendFeatureTouched && trendFeature.value && seriesFeatures.value.includes(trendFeature.value)) return
  trendFeature.value = defaultTrendFeature(point.value?.modalityType, catalog.value, seriesFeatures.value)
}

async function loadBaseline(id: number, request: AbortController) {
  baselineLoad.value = 'LOADING'
  try {
    const item = await getChannelBaseline(id)
    if (!request.signal.aborted) {
      channelBaseline.value = item
      baselineLoad.value = 'READY'
    }
  } catch {
    if (!request.signal.aborted) {
      channelBaseline.value = null
      baselineLoad.value = 'FAILED'
    }
  }
}

async function load() {
  pending?.abort()
  const id = pointId.value
  const rangeValue = selectedRange.value
  if (!Number.isSafeInteger(id) || id < 1) {
    error.value = '测点编号无效'
    return
  }
  const request = new AbortController()
  pending = request
  const range = rangeOptions.find((option) => option.value === rangeValue) || rangeOptions[1]
  const to = new Date()
  const from = new Date(to.getTime() - range.milliseconds)
  const isCurrent = () => !request.signal.aborted && id === pointId.value && rangeValue === selectedRange.value
  error.value = ''
  try {
    if (!point.value) {
      // 首次进入 / 切换测点：先取总览解析出测点及其模态，再按模态走对应分支
      const overview = await getOverview(request.signal)
      if (!isCurrent()) return
      const resolved = overview.points.find((p) => p.pointId === id) ?? null
      if (!resolved) {
        error.value = '未找到该测点，可能已被删除；请从设备清单重新进入。'
        return
      }
      point.value = resolved
      if (isVib.value) {
        // 振动测点：v1 振动遥测
        const history = await getTelemetry(id, from.toISOString(), to.toISOString(), range.interval, request.signal)
        if (!isCurrent()) return
        samples.value = history
        return
      }
      // 非振动测点：v2 通道时序 + 通道基线（不调用振动专用接口）
      const series = await getChannelSeries(id, from.getTime(), to.getTime(), range.step, request.signal)
      if (!isCurrent()) return
      seriesRows.value = series.rows
      seriesFeatures.value = series.features
      syncTrendFeature()
      await loadBaseline(id, request)
      return
    }
    // 模态已知（切换时间范围 / 手动刷新）：总览与该模态的数据请求并发
    const overviewRequest = getOverview(request.signal).then((overview) => {
      if (!isCurrent()) return
      const resolved = overview.points.find((p) => p.pointId === id)
      if (resolved) point.value = resolved
    })
    if (isVib.value) {
      const [, history] = await Promise.all([
        overviewRequest,
        getTelemetry(id, from.toISOString(), to.toISOString(), range.interval, request.signal),
      ])
      if (!isCurrent()) return
      if (!isVib.value) { void load(); return } // 刷新后模态与缓存不符：按新模态重新加载
      samples.value = history
      return
    }
    const [, series] = await Promise.all([
      overviewRequest,
      getChannelSeries(id, from.getTime(), to.getTime(), range.step, request.signal),
    ])
    if (!isCurrent()) return
    if (isVib.value) { void load(); return } // 刷新后模态与缓存不符：按新模态重新加载
    seriesRows.value = series.rows
    seriesFeatures.value = series.features
    syncTrendFeature()
    await loadBaseline(id, request)
  } catch (e) {
    if (isCurrent()) error.value = e instanceof Error ? e.message : '测点数据加载失败'
    request.abort()
  } finally {
    if (pending === request) pending = undefined
  }
}
function draw() {
  chart = ensureChart(chart, chartEl.value)
  if (!chart) return
  const light = theme.value === 'light'
  const label = light ? '#718096' : '#71839a'
  chart.setOption({
    tooltip: {
      trigger: 'axis',
      renderMode: 'richText',
      backgroundColor: light ? '#ffffff' : '#162538',
      borderColor: light ? '#dce4eb' : '#26384d',
      textStyle: { color: light ? '#233247' : '#dce7f4' },
      formatter: (params: unknown) => telemetryTooltip(params, samples.value),
    },
    legend: { textStyle: { color: light ? '#53657a' : '#9aacc4' } },
    grid: { left: 48, right: 24, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: samples.value.map((s) => new Date(s.timestamp).toLocaleTimeString()), axisLabel: { color: label }, axisLine: { lineStyle: { color: light ? '#dce4eb' : '#26384d' } } },
    yAxis: [
      { type: 'value', name: 'mm/s', axisLabel: { color: label, formatter: (value: number) => Number(value).toFixed(2) }, splitLine: { lineStyle: { color: light ? '#e8edf1' : '#1e2d40' } } },
      { type: 'value', name: 'HI 健康指数', min: 0, max: 100, axisLabel: { color: label, formatter: (value: number) => Number(value).toFixed(2) }, splitLine: { show: false } },
    ],
    series: [
      { name: '振动速度有效值（v RMS）', type: 'line', smooth: true, showSymbol: false, data: samples.value.map((s) => s.vRms), itemStyle: { color: light ? '#138b7c' : '#4dd6be' } },
      { name: '健康指数（HI）', type: 'line', yAxisIndex: 1, smooth: true, showSymbol: false, data: samples.value.map((s) => s.healthIndex ?? null), itemStyle: { color: light ? '#bd7a16' : '#f2b35d' } },
    ],
  })
}
watch([samples, theme], draw)
watch([pointId, selectedRange], ([id], [previousId]) => {
  if (id !== previousId) point.value = null
  samples.value = []
  seriesRows.value = []
  seriesFeatures.value = []
  channelBaseline.value = null
  trendFeature.value = ''
  trendFeatureTouched = false
  void load()
})
onMounted(() => {
  draw()
  void load()
  // 模态目录只用于默认趋势特征与新鲜度口径；失败时按模态静态兜底，不影响页面
  void loadModalityCatalog().then((items) => {
    catalogs.value = items
    if (!isVib.value) syncTrendFeature()
  })
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  pending?.abort()
  chart?.dispose()
  window.removeEventListener('resize', resize)
})
function resize() { chart?.resize() }
const rangeOptions = [
  { value: '1h', label: '最近 1 小时', milliseconds: 60 * 60 * 1000, interval: '1m', step: '1m' },
  { value: '24h', label: '最近 24 小时', milliseconds: 24 * 60 * 60 * 1000, interval: '5m', step: '5m' },
  { value: '7d', label: '最近 7 天', milliseconds: 7 * 24 * 60 * 60 * 1000, interval: '1h', step: '1h' },
  { value: '30d', label: '最近 30 天', milliseconds: 30 * 24 * 60 * 60 * 1000, interval: '6h', step: '6h' },
]
</script>

<template>
  <div class="page-stack"><div class="page-intro"><div><RouterLink to="/devices" class="back-link">← 返回资产拓扑</RouterLink><h2>{{ point?.deviceName || '测点详情' }} · {{ point?.pointName || '' }}</h2><p>{{ point?.factoryCode }} / {{ point?.deviceCode }} / {{ point?.pointCode }}<span v-if="point?.modalityType" class="subtle-code"> · 模态：{{ modalityName(point.modalityType) }}</span></p></div><div class="chart-filters"><RouterLink v-if="!isVib" class="quiet-button" :to="`/workbench?pointId=${pointId}`">多模态工作站 →</RouterLink><button class="quiet-button" @click="load">↻ 刷新趋势</button></div></div>
    <div v-if="error" class="notice error-notice">{{ error }}</div>
    <div v-if="isVib" class="metric-explainer">
      <div><b>v RMS · 振动速度有效值</b><span>由振动速度信号计算的均方根值，单位 mm/s，用于反映设备整体振动强度。</span></div>
      <div><b>HI · 健康指数</b><span>0–100 分，结合 1X 振幅相对基线的变化和振动速度评估；分数越低，状态越差。≥70 正常，50–&lt;70 关注，30–&lt;50 警告，&lt;30 危险。</span></div>
    </div>
    <section class="metric-grid compact-metrics"><article class="metric-card"><div class="metric-top"><span>当前健康指数（HI）</span><b class="metric-icon teal">HI</b></div><strong>{{ point?.healthIndex?.toFixed(2) ?? '—' }}</strong><small>{{ pointStatusLabel(point?.status) }} · 综合健康评分</small></article><template v-if="isVib"><article class="metric-card"><div class="metric-top"><span>1X 基线</span><b class="metric-icon blue">1X</b></div><strong>{{ point?.baselineStatus === 1 ? fixed(point?.freq1xBase) : '学习中' }}</strong><small>基线状态 {{ vBaselineMeta.label }}</small></article><article class="metric-card"><div class="metric-top"><span>振动速度有效值基线</span><b class="metric-icon violet">v RMS</b></div><strong>{{ point?.baselineStatus === 1 ? fixed(point?.vBaseRms) : '—' }}</strong><small>单位 mm/s · ISO 10816 Class II</small></article></template><article v-else class="metric-card"><div class="metric-top"><span>通道基线状态</span><b class="metric-icon blue">⌁</b></div><strong>{{ baselineLoad === 'LOADING' ? '加载中…' : baselineMeta.label }}</strong><small>{{ baselineLoad === 'FAILED' ? '通道基线加载失败，请点击“刷新趋势”重试' : channelBaseline ? `${channelBaseline.learnedSamples}/${channelBaseline.minimumSamples} 样本 · ${channelBaseline.featureStates.length} 个特征` : baselineLoad === 'READY' ? '该通道尚未建立基线' : '正在加载通道基线…' }}</small></article><article class="metric-card"><div class="metric-top"><span>聚合趋势点</span><b class="metric-icon amber">⌁</b></div><strong>{{ isVib ? samples.length : seriesRows.length }}</strong><small>{{ rangeOptions.find(option => option.value === selectedRange)?.label }}</small></article></section>
    <section v-if="point" class="panel status-panel">
      <div class="panel-heading">
        <h3>遥测数据状态</h3>
        <span class="status-head">
          <span :class="['status-tag', freshnessState(point.freshness).tone]"><i></i>{{ freshnessState(point.freshness).label }}</span>
          <small v-if="freshnessNote" class="muted status-note">{{ freshnessNote }}</small>
        </span>
      </div>
      <dl class="telemetry-status">
        <div><dt>最新质量</dt><dd>{{ qualityLabel(point.qualityCode) }}</dd></div>
        <div><dt>设备采样时间</dt><dd>{{ telemetryTime(point.sampledAt ?? point.lastTelemetryAt) }}</dd></div>
        <div><dt>平台收到时间</dt><dd>{{ telemetryTime(point.ingestedAt) }}</dd></div>
      </dl>
    </section>
    <section v-if="isVib" class="panel chart-panel"><div class="panel-heading"><div><h3>振动速度有效值与健康指数趋势</h3><p>v RMS（mm/s）与 HI（0–100 分）· {{ rangeOptions.find(option => option.value === selectedRange)?.label }}</p></div><select v-model="selectedRange" class="compact-select"><option v-for="range in rangeOptions" :key="range.value" :value="range.value">{{ range.label }}</option></select></div><div ref="chartEl" class="detail-chart"></div></section>
    <section v-else class="panel chart-panel">
      <div class="panel-heading">
        <div><h3>特征与健康指数趋势</h3><p>{{ trendFeature ? featureTitle(trendFeature) : '通道特征' }} 与 HI（0–100 分）· {{ rangeOptions.find(option => option.value === selectedRange)?.label }}</p></div>
        <div class="chart-filters">
          <select v-if="seriesFeatures.length > 1" v-model="trendFeature" class="compact-select" aria-label="趋势特征" @change="trendFeatureTouched = true"><option v-for="code in seriesFeatures" :key="code" :value="code">{{ featureTitle(code) }}</option></select>
          <select v-model="selectedRange" class="compact-select" aria-label="趋势时间范围"><option v-for="range in rangeOptions" :key="range.value" :value="range.value">{{ range.label }}</option></select>
        </div>
      </div>
      <ChannelTrendChart v-if="seriesRows.length" :rows="seriesRows" :feature="trendFeature" :height="340" />
      <p v-else class="empty-cell">{{ error ? '通道时序暂不可用' : '暂无时序数据' }}</p>
    </section>
    <section v-if="isVib" class="panel table-panel">
      <div class="panel-heading"><div><h3>最新遥测</h3><p>最近 {{ Math.min(20, samples.length) }} 个聚合区间</p></div></div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>时间</th><th>振动速度有效值<small>v RMS · mm/s</small></th><th>1X</th><th>2X</th><th>峭度</th><th>健康指数<small>HI · 0–100 分</small></th><th>状态</th><th>数据质量</th></tr></thead>
          <tbody>
            <tr v-for="sample in samples.slice(-20).reverse()" :key="sample.timestamp">
              <td>{{ telemetryTime(sample.timestamp) }}</td>
              <!-- 缺值（如未上报的可选特征）显示为 —，与 toFixed 位数保持一致 -->
              <td>{{ fixed(sample.vRms) }}</td>
              <td>{{ fixed(sample.freq1xAmp) }}</td>
              <td>{{ fixed(sample.freq2xAmp) }}</td>
              <td>{{ fixed(sample.aKurtosis) }}</td>
              <td>{{ sample.healthIndex?.toFixed(2) ?? '—' }}</td>
              <td>{{ pointStatusLabel(sample.status) }}<small v-if="sample.stopped && sample.status !== 'STOPPED'">含停机采样</small></td>
              <td>{{ qualityLabel(sample.dataQuality) }}</td>
            </tr>
            <tr v-if="!samples.length"><td colspan="8" class="empty-cell">{{ error ? '遥测数据暂不可用' : '暂无时序数据' }}</td></tr>
          </tbody>
        </table>
      </div>
    </section>
    <template v-else>
      <section class="panel table-panel">
        <div class="panel-heading"><div><h3>最新通道特征</h3><p>最近 {{ Math.min(20, seriesRows.length) }} 个聚合区间 · 只列出该区间实际上报的特征</p></div><span class="count-chip">{{ seriesFeatures.length }} 个特征</span></div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>时间</th><th v-for="code in seriesFeatures" :key="code">{{ featureTitle(code) }}<small>{{ code }}</small></th><th>健康指数<small>HI · 0–100 分</small></th><th>状态</th></tr></thead>
            <tbody>
              <tr v-for="row in seriesRows.slice(-20).reverse()" :key="row.timestamp">
                <td>{{ formatTime(row.timestamp) }}</td>
                <td v-for="code in seriesFeatures" :key="code">{{ formatNumber(row.metrics?.[code], 3) }}</td>
                <td>{{ formatNumber(row.health_index, 2) }}</td>
                <td>{{ row.status ? pointStatusLabel(row.status) : '—' }}</td>
              </tr>
              <tr v-if="!seriesRows.length"><td :colspan="seriesFeatures.length + 3" class="empty-cell">暂无时序数据</td></tr>
            </tbody>
          </table>
        </div>
      </section>
      <section class="panel table-panel">
        <div class="panel-heading">
          <div><h3>通道基线矩阵</h3><p>各特征的自学习 P50、变异系数与锁定状态（按特征逐项学习）</p></div>
          <span :class="['status-tag', baselineMeta.tone]"><i></i>基线 {{ baselineLoad === 'FAILED' ? '加载失败' : baselineMeta.label }}</span>
        </div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>特征</th><th>基线 P50</th><th>变异系数 CV</th><th>样本数</th><th>状态</th><th>锁定时间</th></tr></thead>
            <tbody>
              <tr v-for="state in channelBaseline?.featureStates ?? []" :key="state.featureKey">
                <td><b>{{ featureTitle(state.featureKey) }}</b><small class="subtle-code">{{ state.featureKey }}</small></td>
                <td>{{ formatNumber(state.baselineP50, 3) }}</td>
                <td>{{ formatNumber(state.cvValue, 3) }}</td>
                <td>{{ state.sampleCount }}</td>
                <td><span :class="['status-tag', baselineState(state.status).tone]"><i></i>{{ baselineState(state.status).label }}</span></td>
                <td>{{ formatTime(state.lockedAt) }}</td>
              </tr>
              <tr v-if="!channelBaseline?.featureStates?.length"><td colspan="6" class="empty-cell">{{ baselineLoad === 'LOADING' ? '正在加载通道基线…' : baselineLoad === 'FAILED' ? '通道基线加载失败' : '通道基线尚未建立，样本学习中' }}</td></tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.telemetry-status { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; margin: 14px 0 0; }
.telemetry-status dt { color: var(--muted); font-size: 10px; }
.telemetry-status dd { margin: 5px 0 0; font-size: 12px; }
.metric-explainer { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 12px; }
.metric-explainer > div { display: grid; gap: 5px; padding: 14px 16px; border: 1px solid var(--line); border-radius: 12px; background: var(--panel); }
.metric-explainer b { font-size: 12px; }
.metric-explainer span { color: var(--muted); font-size: 11px; line-height: 1.6; }
th small { display: block; margin-top: 3px; font-size: inherit; }
.status-head { display: grid; justify-items: end; gap: 5px; }
.status-note { max-width: 260px; text-align: right; line-height: 1.5; }
</style>
