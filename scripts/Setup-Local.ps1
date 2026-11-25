$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Please use PowerShell 7 or later.' }

$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root '.env'
$secretDir = Join-Path $root 'secrets'
$secretPath = Join-Path $secretDir 'influxdb3-admin-token.json'

if (-not (Test-Path -LiteralPath $envPath)) {
    Copy-Item -LiteralPath (Join-Path $root '.env.example') -Destination $envPath
}

$envLines = Get-Content -LiteralPath $envPath
$adminPasswordLine = $envLines | Where-Object { $_ -match '^PDM_ADMIN_PASSWORD=' } | Select-Object -First 1
$adminPassword = if ($adminPasswordLine) { $adminPasswordLine.Substring('PDM_ADMIN_PASSWORD='.Length).Trim() } else { '' }
if ([string]::IsNullOrWhiteSpace($adminPassword) -or $adminPassword -eq 'change-this-before-start') {
    $adminPassword = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(18)).ToLowerInvariant()
    if ($adminPasswordLine) {
        $envLines = $envLines | ForEach-Object { if ($_ -match '^PDM_ADMIN_PASSWORD=') { "PDM_ADMIN_PASSWORD=$adminPassword" } else { $_ } }
    } else {
        $envLines += "PDM_ADMIN_PASSWORD=$adminPassword"
    }
    Set-Content -LiteralPath $envPath -Value $envLines -Encoding utf8
}

$envLines = Get-Content -LiteralPath $envPath
$tokenLine = $envLines | Where-Object { $_ -match '^PDM_INFLUX_TOKEN=' } | Select-Object -First 1
$token = if ($tokenLine) { $tokenLine.Substring('PDM_INFLUX_TOKEN='.Length).Trim() } else { '' }
if ([string]::IsNullOrWhiteSpace($token) -or $token.StartsWith('generated-by-')) {
    $bytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    $token = 'apiv3_' + [Convert]::ToHexString($bytes).ToLowerInvariant()
    if ($tokenLine) {
        $envLines = $envLines | ForEach-Object { if ($_ -match '^PDM_INFLUX_TOKEN=') { "PDM_INFLUX_TOKEN=$token" } else { $_ } }
    } else {
        $envLines += "PDM_INFLUX_TOKEN=$token"
    }
    Set-Content -LiteralPath $envPath -Value $envLines -Encoding utf8
}

New-Item -ItemType Directory -Path $secretDir -Force | Out-Null
@{ token = $token; name = 'pdm-local-admin'; description = 'Local PdM development token' } |
    ConvertTo-Json -Compress |
    Set-Content -LiteralPath $secretPath -Encoding utf8

Push-Location $root
try {
    docker compose up -d postgres influxdb3 mosquitto
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        $null = docker compose exec -T influxdb3 influxdb3 show databases --token $token 2>$null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw 'InfluxDB 3 did not become ready; inspect: docker compose logs influxdb3' }
    $dbExists = docker compose exec -T influxdb3 influxdb3 show databases --token $token 2>$null | Select-String -SimpleMatch 'pdm-ts-data'
    if (-not $dbExists) {
        docker compose exec -T influxdb3 influxdb3 create database --retention-period 365d --token $token pdm-ts-data
        if ($LASTEXITCODE -ne 0) { throw 'Failed to initialize InfluxDB database pdm-ts-data' }
    }
    docker compose up -d --build app
    if ($LASTEXITCODE -ne 0) { throw 'Failed to build or start the application; inspect: docker compose logs app' }
    Write-Host 'PdM MVP is starting at http://localhost:8080'
    Write-Host "Initial admin: admin / $adminPassword"
} finally {
    Pop-Location
}
