param([switch]$Offline,[switch]$SkipBuild,[ValidateSet('Configured','demo')][string]$Mode='Configured')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskLocal=Join-Path $taskRoot '.local'
New-Item -ItemType Directory -Path $taskLocal -Force | Out-Null
$taskEnv=Join-Path $taskRoot '.env'
if (!(Test-Path -LiteralPath $taskEnv)) { Copy-Item -LiteralPath (Join-Path $taskRoot '.env.example') -Destination $taskEnv }
& (Join-Path $PSScriptRoot 'init-env.ps1')
$taskSavedEnv=@{}
foreach($taskKey in @('LLM_ENABLED','LANGSMITH_TRACING','SPRING_PROFILES_ACTIVE','DB_URL','ASYNC_MODE','PGVECTOR_ENABLED')) { $taskSavedEnv[$taskKey]=[Environment]::GetEnvironmentVariable($taskKey,'Process') }
try {
if ($Offline) { $env:LLM_ENABLED='false'; $env:LANGSMITH_TRACING='false' }
if ($Mode -eq 'demo') {
    $env:SPRING_PROFILES_ACTIVE='demo'
    $env:DB_URL='jdbc:h2:file:./data/runtime/smartserve-demo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE'
    $env:ASYNC_MODE='local'
    $env:PGVECTOR_ENABLED='false'
}
foreach ($taskPort in @(8080,5173)) {
    if (Get-NetTCPConnection -LocalPort $taskPort -State Listen -ErrorAction SilentlyContinue) { throw "端口 $taskPort 已被占用；请先停止之前的服务。" }
}
if (!$SkipBuild) {
    $taskMaven=$env:MAVEN_EXECUTABLE
    if (!$taskMaven) {
        $taskMavenLine=Get-Content -LiteralPath $taskEnv -Encoding UTF8 | Where-Object { $_ -like 'MAVEN_EXECUTABLE=*' } | Select-Object -First 1
        if ($taskMavenLine) { $taskMaven=$taskMavenLine.Substring('MAVEN_EXECUTABLE='.Length).Trim().Trim('"') }
    }
    if (!$taskMaven) { $taskMaven='mvn.cmd' }
    $taskMavenArgs=@('-B','-ntp','package','-DskipTests')
    $taskSettings=Join-Path $taskLocal 'maven-settings.xml'
    if (Test-Path -LiteralPath $taskSettings) { $taskMavenArgs+=@('-s',$taskSettings) }
    $taskRepository=Join-Path $taskLocal 'm2'
    if (Test-Path -LiteralPath $taskRepository) { $taskMavenArgs+="-Dmaven.repo.local=$taskRepository" }
    Push-Location (Join-Path $taskRoot 'backend')
    try { & $taskMaven @taskMavenArgs; if ($LASTEXITCODE -ne 0) { throw '后端构建失败；请使用 Maven 3.9 并查看构建输出。' } } finally { Pop-Location }
}
$taskJar=Join-Path $taskRoot 'backend\target\smartserve-backend-2.0.0.jar'
if (!(Test-Path -LiteralPath $taskJar)) { throw '找不到后端JAR；请先构建项目。' }
Push-Location (Join-Path $taskRoot 'frontend')
try {
    if (!(Test-Path -LiteralPath 'node_modules')) {
        & npm.cmd ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' }
    }
} finally { Pop-Location }
$taskBackend=Start-Process -FilePath 'java.exe' -ArgumentList @('-jar',('"'+$taskJar+'"')) -WorkingDirectory (Join-Path $taskRoot 'backend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskLocal 'backend.out.log') -RedirectStandardError (Join-Path $taskLocal 'backend.err.log') -PassThru
$taskBackend.Id | Set-Content -LiteralPath (Join-Path $taskLocal 'backend.pid')
$taskVite=Join-Path $taskRoot 'frontend\node_modules\vite\bin\vite.js'
$taskFrontend=Start-Process -FilePath 'node.exe' -ArgumentList @(('"'+$taskVite+'"'),'--host','127.0.0.1') -WorkingDirectory (Join-Path $taskRoot 'frontend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskLocal 'frontend.out.log') -RedirectStandardError (Join-Path $taskLocal 'frontend.err.log') -PassThru
$taskFrontend.Id | Set-Content -LiteralPath (Join-Path $taskLocal 'frontend.pid')
Write-Host '正在加载中文向量模型与知识索引，首次启动可能需要 30–90 秒。'
$taskReady=$false
$taskDeadline=[DateTime]::UtcNow.AddSeconds(180)
while([DateTime]::UtcNow -lt $taskDeadline){
    $taskBackend.Refresh()
    if($taskBackend.HasExited){Stop-Process -Id $taskFrontend.Id -ErrorAction SilentlyContinue;throw '后端启动失败，请查看 .local/backend.out.log 和 .local/backend.err.log'}
    try {
        $taskStatus=Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/status' -TimeoutSec 2
        if($taskStatus.knowledge.chunks -gt 0){$taskReady=$true;break}
    } catch { }
    Start-Sleep -Milliseconds 800
}
if(!$taskReady){throw '启动仍未就绪，进程保留用于排查；请查看 .local/ 日志，或执行 scripts/stop.ps1 后重试。'}
Write-Host '服务和知识索引已就绪：访问 http://localhost:5173 ；运行日志在 .local/。'
Write-Host '停止项目：powershell -ExecutionPolicy Bypass -File scripts/stop.ps1'

} finally {
    foreach($taskKey in $taskSavedEnv.Keys) { [Environment]::SetEnvironmentVariable($taskKey,$taskSavedEnv[$taskKey],'Process') }
}
