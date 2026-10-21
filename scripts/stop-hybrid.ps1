$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'stop.ps1')
Push-Location $taskRoot
try {
    & docker compose stop postgres redis rabbitmq
    if ($LASTEXITCODE -ne 0) { throw '项目容器未能停止，请确认Docker Desktop引擎正在运行。' }
} finally { Pop-Location }
Write-Host '已停止本项目前后端和三个基础设施容器，数据卷保留。'
