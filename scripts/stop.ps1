$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
foreach ($taskName in @('backend','frontend')) {
    $taskPidFile=Join-Path $taskRoot ".local\$taskName.pid"
    if (!(Test-Path -LiteralPath $taskPidFile)) { continue }
    $taskPid=[int](Get-Content -LiteralPath $taskPidFile)
    $taskProcess=Get-CimInstance Win32_Process -Filter "ProcessId = $taskPid" -ErrorAction SilentlyContinue
    if ($taskProcess -and $taskProcess.CommandLine -and $taskProcess.CommandLine.Contains($taskRoot)) { Stop-Process -Id $taskPid -ErrorAction SilentlyContinue; Write-Host "已停止 $taskName" }
    Remove-Item -LiteralPath $taskPidFile -ErrorAction SilentlyContinue
}
Write-Host '停止程序不会删除数据库或Docker卷。'
