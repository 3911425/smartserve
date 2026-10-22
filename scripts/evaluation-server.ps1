param([switch]$Online,[switch]$Trace,[switch]$Stop)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskFolder=Join-Path $taskRoot '.local\evaluation'
$taskPidFile=Join-Path $taskFolder 'backend.pid'
if($Stop){
    if(Test-Path -LiteralPath $taskPidFile){
        $taskPid=[int](Get-Content -LiteralPath $taskPidFile)
        $taskProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$taskPid" -ErrorAction SilentlyContinue
        if($taskProcess -and $taskProcess.CommandLine.Contains('server.port=18082')){Stop-Process -Id $taskPid}
        Remove-Item -LiteralPath $taskPidFile
    }
    Write-Host 'Evaluation server stopped; fixtures are retained in .local/evaluation/.'
    exit
}
if(Get-NetTCPConnection -LocalPort 18082 -State Listen -ErrorAction SilentlyContinue){throw 'Port 18082 is busy. Stop the existing evaluation server first.'}
& (Join-Path $PSScriptRoot 'init-env.ps1')
New-Item -ItemType Directory -Path $taskFolder -Force | Out-Null
$taskJar=Join-Path $taskRoot 'backend\target\smartserve-backend-2.0.0.jar'
if(!(Test-Path -LiteralPath $taskJar)){throw 'Build the V2 backend JAR first.'}
$taskLlm=if($Online){'true'}else{'false'}
$taskTracing=if($Trace){'true'}else{'false'}
$taskJdbc='jdbc:h2:file:'+($taskFolder.Replace('\','/'))+'/database;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE'
$taskArgs=@('-jar',('"'+$taskJar+'"'),'--server.port=18082','--spring.profiles.active=demo',('--spring.datasource.url='+$taskJdbc),'--spring.datasource.username=sa','--spring.datasource.password=','--smartserve.async-mode=local','--smartserve.pgvector-enabled=false',('--smartserve.llm-enabled='+$taskLlm),('--smartserve.tracing-enabled='+$taskTracing),'--WORKER_ENABLED=true')
$taskProcess=Start-Process -FilePath 'java.exe' -ArgumentList $taskArgs -WorkingDirectory (Join-Path $taskRoot 'backend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskFolder 'out.log') -RedirectStandardError (Join-Path $taskFolder 'err.log') -PassThru
$taskProcess.Id | Set-Content -LiteralPath $taskPidFile
Write-Host "Evaluation API: http://127.0.0.1:18082; model enabled: $taskLlm. Wait until /api/status responds."
