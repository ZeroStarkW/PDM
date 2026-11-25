import { init, use } from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { DataZoomComponent, DataZoomInsideComponent, DataZoomSliderComponent, GridComponent, LegendComponent, MarkAreaComponent, MarkLineComponent, MarkPointComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([LineChart, BarChart, GridComponent, LegendComponent, TooltipComponent, DataZoomComponent, DataZoomInsideComponent, DataZoomSliderComponent, MarkLineComponent, MarkAreaComponent, MarkPointComponent, CanvasRenderer])

export const initChart = init
export type { EChartsType } from 'echarts/core'
