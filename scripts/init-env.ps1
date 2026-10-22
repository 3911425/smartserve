param()
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskEnvPath=Join-Path $taskRoot '.env'
if (!(Test-Path -LiteralPath $taskEnvPath)) { Copy-Item -LiteralPath (Join-Path $taskRoot '.env.example') -Destination $taskEnvPath }
$taskEnvText=[IO.File]::ReadAllText($taskEnvPath)
function New-TaskSecret {
    $taskBytes=New-Object byte[] 24
    $taskRng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try { $taskRng.GetBytes($taskBytes) } finally { $taskRng.Dispose() }
    return [Convert]::ToBase64String($taskBytes).Replace('+','x').Replace('/','y').TrimEnd('=')
}
foreach ($taskEntry in @('BOOTSTRAP_ADMIN_PASSWORD','BOOTSTRAP_CUSTOMER_PASSWORD','SESSION_SIGNING_SECRET','MCP_SERVICE_TOKEN')) {
    $taskPattern='(?m)^'+[regex]::Escape($taskEntry)+'=(.*)$'
    $taskMatch=[regex]::Match($taskEnvText,$taskPattern)
    if (!$taskMatch.Success) { $taskEnvText=$taskEnvText.TrimEnd()+[Environment]::NewLine+$taskEntry+'='+(New-TaskSecret)+[Environment]::NewLine }
    elseif ($taskMatch.Groups[1].Value.Trim().Trim('"') -eq '' -or $taskMatch.Groups[1].Value -match '^(local-demo|replace-|change-me)') {
        $taskEnvText=[regex]::Replace($taskEnvText,$taskPattern,$taskEntry+'='+(New-TaskSecret))
    }
}
if ($taskEnvText -notmatch '(?m)^BOOTSTRAP_ADMIN_USER=') { $taskEnvText+='BOOTSTRAP_ADMIN_USER=admin'+[Environment]::NewLine }
[IO.File]::WriteAllText($taskEnvPath,$taskEnvText,[Text.UTF8Encoding]::new($false))
Write-Host 'Local account initialization is ready. Credentials are stored only in .env (BOOTSTRAP_ADMIN_PASSWORD / BOOTSTRAP_CUSTOMER_PASSWORD).'
Write-Host 'Admin username: BOOTSTRAP_ADMIN_USER; legacy customer username: demo. Existing database passwords are never overwritten.'
