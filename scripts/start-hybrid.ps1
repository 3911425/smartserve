param([switch]$Offline,[switch]$SkipBuild)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'init-env.ps1')

# Start only this project's infrastructure. The existing Redis and Windows PG are independent.
foreach ($taskPort in @(8080,5173)) {
    if (Get-NetTCPConnection -LocalPort $taskPort -State Listen -ErrorAction SilentlyContinue) {
        throw "端口 $taskPort 已被占用，请先停止当前运行的前后端。"
    }
}
if (!(Get-Command docker -ErrorAction SilentlyContinue)) { throw '找不到Docker命令，请先安装Docker Desktop。' }
$taskErrorPreference=$ErrorActionPreference
try {
    $ErrorActionPreference='Continue'
    & docker info --format '{{.ServerVersion}}' *> $null
    $taskDockerInfoCode=$LASTEXITCODE
} finally { $ErrorActionPreference=$taskErrorPreference }
if ($taskDockerInfoCode -ne 0) { throw '请先打开Docker Desktop，等引擎运行后重试。' }

Push-Location $taskRoot
try {
    & docker compose up -d --wait --wait-timeout 180 postgres redis rabbitmq
    if ($LASTEXITCODE -ne 0) { throw '项目基础设施未能就绪，请查看docker compose logs。' }
} finally { Pop-Location }

# These overrides apply only to the child processes launched below. Never edit .env or credentials.
$taskOverrides=@{
    SPRING_PROFILES_ACTIVE='postgres'
    DB_URL='jdbc:postgresql://127.0.0.1:15432/smartserve'
    REDIS_HOST='127.0.0.1'
    REDIS_PORT='16379'
    RABBIT_HOST='127.0.0.1'
    RABBIT_PORT='5672'
    PGVECTOR_ENABLED='true'
    ASYNC_MODE='rabbit'
}
$taskPrevious=@{}
foreach ($taskKey in @($taskOverrides.Keys)+@('LLM_ENABLED','LANGSMITH_TRACING')) {
    $taskPrevious[$taskKey]=[Environment]::GetEnvironmentVariable($taskKey,'Process')
}
try {
    foreach ($taskKey in $taskOverrides.Keys) {
        [Environment]::SetEnvironmentVariable($taskKey,$taskOverrides[$taskKey],'Process')
    }
    & (Join-Path $PSScriptRoot 'start.ps1') -Offline:$Offline -SkipBuild:$SkipBuild
    Write-Host '混合运行：前后端在本机，项目PostgreSQL、Redis和RabbitMQ在Docker。'
    Write-Host '停止整套混合运行：powershell -ExecutionPolicy Bypass -File scripts/stop-hybrid.ps1'
} finally {
    foreach ($taskKey in $taskPrevious.Keys) {
        [Environment]::SetEnvironmentVariable($taskKey,$taskPrevious[$taskKey],'Process')
    }
}
