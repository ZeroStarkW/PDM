# 多模态演示数据发布脚本（PHM 协议 2.1.0）
# 用途：向本地 MQTT（docker compose 里的 mosquitto）发布 10kV 断路器遥测、feed 调节阀过程遥测
#       与一条断路器动作快照，让演示种子（pdm.demo-data.enabled=true）建出的两个多模态工作台
#       逐步走出学习期并出现实时曲线。
#
# 前置条件：
#   1. scripts/Setup-Local.ps1 已执行（postgres / influxdb3 / mosquitto / app 已启动）。
#   2. .env 中 PDM_DEMO_DATA=true（演示种子生效）。
#
# 注意（重要）：
#   - 调节阀（CONTROL_VALVE_PROCESS）评分要求其 1h 学习窗口内累计约 200 个稳态样本。
#     本脚本每轮发 9 个阀样本、间隔约 1.1s，一轮约 10s —— 需要持续运行约 15 分钟
#     （或多开几个终端并行）才能进入评分；否则该通道保持 BASELINE_LEARNING，
#     前端展示 PROVISIONAL 基线与 learning 帧（health_index = null），属正常空态。
#   - 断路器（SWITCHGEAR_EVENT）为事件型 pooled 学习策略，少量动作事件即可参与统计。
#   - 本脚本不直接写 InfluxDB；全部数据经平台 MQTT 入库（校验、基线、评分、告警链路一致）。
#
# 用法： pwsh ./scripts/Publish-MultimodalSamples.ps1 [-ValveCycles 90] [-IncludeSnapshot]
#       -ValveCycles      调节阀周期轮数（默认 90 轮 ≈ 15 分钟；0 = 只发断路器）
#       -IncludeSnapshot  额外发布一条断路器 TRIP_ACTION_BURST 快照（默认开启）

param(
    [int]$ValveCycles = 90,
    [switch]$IncludeSnapshot = $true
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Please use PowerShell 7 or later.' }

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    # 断路器遥测：一次分闸动作事件（协议 2.1.0 phm/telemetry/{asset_id}/{channel_id}）
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $breakerJson = @{
        protocol_version   = '2.1.0'
        timestamp          = $ts
        asset_id           = 'BREAKER_10KV_02'
        channel_id         = 'CH_TRIP_UNIT'
        modality_type      = 'SWITCHGEAR_EVENT'
        data_quality       = 0
        metrics            = @{
            trip_duration_ms     = 41.8
            peak_coil_current_a  = 5.31
            closing_velocity_mps = 2.18
            contact_wear_ratio   = 0.11
        }
    } | ConvertTo-Json -Depth 4 -Compress
    $breakerTopic = 'phm/telemetry/BREAKER_10KV_02/CH_TRIP_UNIT'
    docker compose exec -T mosquitto mosquitto_pub -h localhost -q 1 -t $breakerTopic -m $breakerJson
    if ($LASTEXITCODE -ne 0) { throw "Failed to publish breaker telemetry to $breakerTopic" }
    Write-Host "Published breaker event -> $breakerTopic"

    if ($IncludeSnapshot) {
        # 断路器动作快照（设计 §2.2 十点曲线，标注由平台侧计算）
        $snapshotJson = @{
            protocol_version = '2.1.0'
            timestamp        = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            asset_id         = 'BREAKER_10KV_02'
            channel_id       = 'CH_TRIP_UNIT'
            modality_type    = 'SWITCHGEAR_EVENT'
            trigger_type     = 'TRIP_ACTION_BURST'
            sampling_rate    = 10000
            points_count     = 10
            preview_curve    = @(0.0, 0.8, 3.2, 5.42, 4.1, 2.0, 0.5, 0.1, 0.0, 0.0)
            curves           = @{
                time_axis_ms     = @(0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0, 40.0, 45.0)
                coil_current_a   = @(0.0, 0.8, 3.2, 5.42, 4.1, 2.0, 0.5, 0.1, 0.0, 0.0)
                travel_stroke_mm = @(0.0, 0.0, 0.2, 1.5, 4.8, 9.2, 14.5, 18.2, 20.0, 20.0)
            }
            curve_units      = @{ time_axis_ms = 'ms'; coil_current_a = 'A'; travel_stroke_mm = 'mm' }
            metrics          = @{ trip_duration_ms = 42.5; peak_coil_current_a = 5.42 }
            health_index     = 96.5
            data_quality     = 0
        } | ConvertTo-Json -Depth 5 -Compress
        $snapshotTopic = 'phm/snapshot/BREAKER_10KV_02/CH_TRIP_UNIT'
        docker compose exec -T mosquitto mosquitto_pub -h localhost -q 1 -t $snapshotTopic -m $snapshotJson
        if ($LASTEXITCODE -ne 0) { throw "Failed to publish snapshot to $snapshotTopic" }
        Write-Host "Published breaker snapshot -> $snapshotTopic"
    }

    # 调节阀遥测：稳态行程周期（cv 指令 / fb 反馈滞后约 2%），工况恒为稳态
    # 每个样本间隔约 1.1s，保证毫秒时间戳严格递增（平台按时间戳去重/排序）
    $valveTopic = 'phm/telemetry/FEED_VALVE_FCV101/CH_VALVE_LOOP'
    $cycle = @(
        @{ cv = 0.0;  fb = 0.0 },
        @{ cv = 25.0; fb = 23.0 },
        @{ cv = 50.0; fb = 48.0 },
        @{ cv = 75.0; fb = 73.0 },
        @{ cv = 75.0; fb = 75.0 },
        @{ cv = 50.0; fb = 50.0 },
        @{ cv = 25.0; fb = 25.0 },
        @{ cv = 0.0;  fb = 2.0 },
        @{ cv = 0.0;  fb = 0.0 }
    )
    for ($cycleIndex = 0; $cycleIndex -lt $ValveCycles; $cycleIndex++) {
        foreach ($step in $cycle) {
            $valveJson = @{
                protocol_version    = '2.1.0'
                timestamp           = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                asset_id            = 'FEED_VALVE_FCV101'
                channel_id          = 'CH_VALVE_LOOP'
                modality_type       = 'CONTROL_VALVE_PROCESS'
                data_quality        = 0
                operating_condition = @{ steady_state = $true; load_pct = 80.0 }
                metrics             = @{
                    cv_pct                  = $step.cv
                    fb_pct                  = $step.fb
                    hysteresis_deadband_pct = 1.0
                    travel_time_lag_ms      = 400.0
                    ultrasonic_leak_rms     = 0.04
                    friction_force_kn       = 1.2
                }
            } | ConvertTo-Json -Depth 4 -Compress
            docker compose exec -T mosquitto mosquitto_pub -h localhost -q 1 -t $valveTopic -m $valveJson
            if ($LASTEXITCODE -ne 0) { throw "Failed to publish valve telemetry to $valveTopic" }
            Start-Sleep -Milliseconds 1100
        }
        Write-Host ("Valve cycle {0}/{1} published -> {2}" -f ($cycleIndex + 1), $ValveCycles, $valveTopic)
    }
    Write-Host 'Done. Open the multimodal workbenches to watch the channels leave LEARNING.'
    Write-Host 'Note: the valve loop chart needs live telemetry (this script) - the demo seed only inserts one snapshot.'
} finally {
    Pop-Location
}
