<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { EChartsType } from '@/charts'
import type { ChannelSeriesRow } from '@/api/types'
import { useTheme } from '@/composables/ui'
import { featureInfo, formatNumber, formatTime } from '@/modality'
import { pointStatusLabel } from '@/telemetry-status'
import { chartTheme, ensureChart } from './shared'

/** 跨模态统一趋势图：左轴特征量、右轴健康指数（0–100）；时间轴按行时间戳的真实间距绘制。 */
const props = withDefaults(defineProps<{ rows: ChannelSeriesRow[]; feature: string; height?: number; legend?: boolean }>(), { height: 260, legend: true })
const { theme } = useTheme()
const el = ref<HTMLDivElement>()
let chart: EChartsType | undefined

function tooltip(params: unknown) {
  if (!Array.isArray(params) || !params.length) return ''
  const index = (params[0] as { dataIndex?: number }).dataIndex
  const row = typeof index === 'number' ? props.rows[index] : undefined
  if (!row) return ''
  const { label, unit } = featureInfo(props.feature)
  return [
    formatTime(row.timestamp),
    `${label}  ${formatNumber(row.metrics?.[props.feature], 3)} ${unit}`.trim(),
    `健康指数  ${formatNumber(row.health_index, 1)}`,
    row.status ? pointStatusLabel(row.status) : '',
  ].filter(Boolean).join('\n')
}

function draw() {
  // 容器不存在时不建图；容器被重建时 ensureChart 会丢弃绑定到旧容器的实例
  chart = ensureChart(chart, el.value)
  if (!chart) return
  const palette = chartTheme()
  const { label, unit } = featureInfo(props.feature)
  const points = (pick: (row: ChannelSeriesRow) => number | null) => props.rows.map((row) => [row.timestamp, pick(row)])
  const showSymbol = props.rows.length <= 80
  const featureSeries = props.feature
    ? [{ id: 'feature', name: label, type: 'line' as const, showSymbol, animation: false, data: points((row) => row.metrics?.[props.feature] ?? null), lineStyle: { color: palette.feature, width: 1.5 }, itemStyle: { color: palette.feature } }]
    : []
  const healthSeries = { id: 'health', name: '健康指数（HI）', type: 'line' as const, yAxisIndex: 1, showSymbol, animation: false, data: points((row) => row.health_index ?? null), lineStyle: { color: palette.hi, width: 1.5 }, itemStyle: { color: palette.hi } }
  chart.setOption({
    tooltip: { trigger: 'axis', ...palette.tooltip, formatter: tooltip },
    legend: { show: props.legend, top: 0, textStyle: { color: palette.label }, data: [...featureSeries, healthSeries].map((item) => item.name) },
    grid: { left: 60, right: 56, top: props.legend ? 40 : 16, bottom: 28 },
    xAxis: { type: 'time', axisLabel: { color: palette.axis, hideOverlap: true }, axisLine: { lineStyle: { color: palette.split } } },
    yAxis: [
      { type: 'value', name: props.feature ? (unit ? `${label} (${unit})` : label) : undefined, scale: true, nameTextStyle: { color: palette.axis }, axisLabel: { color: palette.axis }, splitLine: { lineStyle: { color: palette.split } } },
      { type: 'value', name: '健康指数 HI (0–100)', min: 0, max: 100, nameTextStyle: { color: palette.axis }, axisLabel: { color: palette.axis }, splitLine: { show: false } },
    ],
    series: [...featureSeries, healthSeries],
    // 按 id 合并更新而不是 notMerge 整体重建：实时帧到来只刷新数据，坐标轴/图例/缩放状态不丢；
    // replaceMerge 保证取消特征量时旧的 feature 序列被移除，lazyUpdate 把同一帧内的多次更新合并到下一次绘制。
  }, { replaceMerge: ['series'], lazyUpdate: true })
}

function resize() { chart?.resize() }

watch([() => props.rows, () => props.feature, theme], draw)
watch(() => props.height, resize)
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
  <div ref="el" class="trend-canvas" :style="{ height: `${height}px` }"></div>
</template>

<style scoped>
.trend-canvas { width: 100%; }
</style>
