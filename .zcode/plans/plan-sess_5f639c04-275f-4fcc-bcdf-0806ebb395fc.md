# 频谱与健康基线可视化对比:先 B 后 A

## Context(为什么做)

当前基线是**标量特征级**(每特征 P50+CV,存于 `pdm_baseline_feature_state`),而专家工作台的 FFT 频谱图只画当前帧 + 1X~6X 谐波光标,画面上没有任何"健康参考",用户无法判断谱线是否正常。HI 打分核心公式 `k = A_curr/A_base`(`HealthScale.java:10-15`)的信息已在后端,却没在频谱上露出。

分两阶段:
- **阶段 B(轻量)**:FFT 谐波光标与包络谱故障线上叠加基线 P50 锚点 + 倍率 k 读数。纯前端,复用已有数据。
- **阶段 A(根本解)**:频谱基线带——对历史 FFT/包络谱帧按频带聚合,复用现有 MAD 清洗内核学出每带 P50±3×1.4826×MAD 参考带,前端画半透明参考带 + 超限高亮。

## 阶段 B:谐波基线锚点(仅前端)

数据源已就绪:`WorkbenchView.vue:291` 已对所有测点(含振动)调用 `getChannelBaseline(point.id)`,经 `props.shared.baseline`(`SharedChannelData`,`shared.ts:22-30`)传入工作台;振动特征状态行由 `BaselineService.observeVibration` 双写,含 `freq_1x_amp`/`freq_2x_amp`/`feat_3x_amp`/`envelope_bpfo_amp`/`env_bpfi_amp` 的 P50(`VibrationContractAdapter.java:12-25`)。

改 `frontend/src/components/workbenches/RotatingVibrationWorkbench.vue`:

1. **读取基线**:从 `props.shared?.baseline?.featureStates` 建 `Map<code, {p50, status}>`;无 shared 时兜底自行调用 `getChannelBaseline`(参照 `ControlValveHysteresisWorkbench.vue:155` 的优先 shared 模式)。
2. **FFT 图锚点**(`draw()` 内 fftChart 分支):在 1X/2X/3X 谐波光标处追加水平短参考线段(markLine 双 coord 点 `[{coord:[hz-Δ,p50]},{coord:[hz+Δ,p50]}]`,参照 `ControlValveHysteresisWorkbench.vue:116-122` 的双点写法),Δ 取相邻谐波间距的 15%;标签 `基线 0.42 · k=2.1×`。
3. **劣化高亮**:`k = 当前谐波处 BandRms 思路的幅值/P50 > 2.5`(文档拐点半衰倍率 `k_mid=2.5`)时,该谐波光标改用 `palette.red` 并在标签加 `劣化` 字样。当前幅值取谐波 Hz 附近 bin 的能量和(前端简单实现:±3% 频带内 amps 平方和开方)。
4. **包络谱锚点**:BPFO/BPFI 故障线处同样画基线包络 P50 参考段(故障线 Hz 后端帧已给出),标签含倍率。
5. **状态语义**:仅 `status === 'ACTIVE'` 的特征行显示实色锚点;`PROVISIONAL`/`LEARNING` 行用 `palette.muted` 虚线并标注"学习预览";基线整体无数据时不画锚点(保持现状)。
6. 面板标题副文案补充一句:"虚线为基线 P50 参考,k=当前/基线 倍率"。

无前端测试设施,以 `vue-tsc` 类型检查为门禁。

## 阶段 A:频谱基线带

### 后端

**频带网格**(`analytics/SpectrumBandGrid.java` 新建):1/6 倍频程对数频带,2Hz–2000Hz(FFT,约 92 带);包络谱同函数截到 500Hz(约 66 带)。每带值 = 带内 bin 幅值平方和开方(能量和)。

**V17 迁移**(`src/main/resources/db/migration/V17__spectrum_baseline_band.sql`,仿 V11 的 DDL 风格):
- `pdm_spectrum_band_sample`:point_id、kind VARCHAR(16)('FFT'|'ENVELOPE')、sampled_at、band_values JSONB(与网格对齐的数组),`UNIQUE(point_id, kind, sampled_at)` + point/time 索引 —— 滚动窗口原始样本,仿 `pdm_baseline_sample_v2`。
- `pdm_spectrum_band_state`:point_id、kind、band_index、band_low_hz/center_hz/high_hz、p50、mad、sample_count、`UNIQUE(point_id, kind, band_index)`。

**`analytics/SpectrumBaselineService.java` 新建**:
- `observe(PointEntity, Waveframe)`:仅 ROTATING_VIB;**工况门禁与标量学习一致**(转速偏差 >5% 剔除,参照 `BaselineService.java:76-77`);LEARNING 期间写样本 + 滚动窗口清理(仿 `BaselineService.java:89-93`);样本数达 `pdm.spectrum-baseline.minimum-samples`(默认 30)→ 逐带 MAD 清洗后写 band_state(P50/MAD),状态冻结为已锁定 —— **独立于测点标量基线的稀疏帧冻结判据**(波形帧仅 THRESHOLD/SCHEDULED/ON_DEMAND 触发,沿用 200 样本/6 分钟判据永远到不了)。
- `get(pointId)`:返回 `{status, sampleCount, minimumSamples, bands:[{kind, centerHz, lowHz, highHz, p50, upper, lower}]}`,`upper = p50 + 3×1.4826×mad`(与 `madClean` 同口径)。
- 复用 `BaselineService` 的静态 `madClean`/`median`(已是包级可见 static)。

**接线**:
- `waveform/WaveformService.accept`(`:84` `frames.analyze(snapshot)` 之后)调用 `SpectrumBaselineService.observe` —— waveform→analytics 无循环依赖(analytics 不引 waveform)。
- `waveform/DiagnosticsController` 加 `GET /api/v1/diagnostics/{pointId}/spectrum-baseline`。
- `BaselineService.confirmReset`(`:355-375`)同步删除两张频谱表该测点数据,保持"一条重置路径"。

**配置**(`application.yml`,仿现有 `pdm.*` 风格):`pdm.spectrum-baseline.minimum-samples: ${PDM_SPECTRUM_MIN_SAMPLES:30}`、`window: ${PDM_SPECTRUM_WINDOW:P30D}`;用 `@Value` 注入(简单值,参照 `BaselineService.java:51`)。

### 前端

- `types.ts` 加 `SpectrumBaseline` 类型;`pdm.ts` 加 `getSpectrumBaseline(pointId)` → `GET /api/v1/diagnostics/{id}/spectrum-baseline`。
- `RotatingVibrationWorkbench.vue`:
  - 挂载时与"刷新快照"时拉取频谱基线(REST 快照模式同样可用)。
  - FFT/包络图画参考带:**堆叠面积法**(下界 series stack 透明 + 上-下界 series stack 半透明灰 `areaStyle`),x 用带中心 Hz;当前谱超出上界的 bin 以红色散点 series 叠加标出。
  - 面板标题显示基线状态(`学习中 n/30` / `已锁定 · 时间`),工具条加开关"基线带"可隐藏。

### 测试(纯 JUnit5+Mockito,无 SpringBootTest,仿现有风格)

- `analytics/SpectrumBandGridTest`:网格边界、带对齐、能量和计算。
- `analytics/SpectrumBaselineServiceTest`:样本累积→冻结、MAD 上下限、转速门禁剔除、重置清空、样本不足不冻结。
- `waveform/WaveformServiceTest` 补充:accept 后 observe 被调用。

### 文档

`pdm_mvp.md` §4.2 附近补一小节:频谱基线带的冻结判据(独立计数冻结 + 同一重置路径)与"超上界高亮"语义,注明告警接入为后续项(MVP 仅展示)。

## 验证

1. 后端:`mvn -o -Dskip.installnodenpm -Dskip.npm test`(现有 171 测试全绿 + 新增测试)。
2. 前端:`frontend/` 下 `npm run build`(vue-tsc 类型门禁)。
3. 手动:`docker compose up` 后打开工作站振动视图 —— 阶段 B 看 1X/2X/3X 光标基线锚点与 k 读数、BPFO 故障线参考段;阶段 A 用演示数据喂若干快照帧后确认参考带渲染、超限点标红、"基线带"开关与状态文案;重置基线后锚点/带随之清空重学。

两阶段分别完成后各自向你汇报;不主动提交 commit(工作区已有你未提交的改动)。