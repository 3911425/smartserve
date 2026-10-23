param()
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskLogs=Join-Path $taskRoot '.local\verification'
New-Item -ItemType Directory -Path $taskLogs -Force | Out-Null
$taskConfig=@{}
foreach($taskLine in Get-Content -LiteralPath (Join-Path $taskRoot '.env') -Encoding UTF8){
    if($taskLine -match '^([A-Z_]+)=(.*)$'){$taskConfig[$Matches[1]]=$Matches[2].Trim().Trim('"')}
}
$taskMaven=$taskConfig['MAVEN_EXECUTABLE']
if(!$taskMaven){$taskMaven='mvn.cmd'}
$taskArgs=@('-B','-ntp')
if(Test-Path -LiteralPath (Join-Path $taskRoot '.local\maven-settings.xml')){$taskArgs+=@('-s',(Join-Path $taskRoot '.local\maven-settings.xml'))}
if(Test-Path -LiteralPath (Join-Path $taskRoot '.local\m2')){$taskArgs+="-Dmaven.repo.local=$(Join-Path $taskRoot '.local\m2')"}
$taskKeys=@('DB_USER','DB_PASSWORD','DB_URL','SMARTSERVE_PARITY_TEST','SMARTSERVE_PARITY_DB_URL','SMARTSERVE_PARITY_PGVECTOR','SMARTSERVE_STANDARD_TEST','SMARTSERVE_TEST_QUEUE','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD','RABBIT_HOST','RABBIT_PORT','RABBIT_USER','RABBIT_PASSWORD')
$taskPrevious=@{}
foreach($taskKey in $taskKeys){$taskPrevious[$taskKey]=[Environment]::GetEnvironmentVariable($taskKey,'Process')}
$taskResults=[Collections.Generic.List[object]]::new()
function Invoke-TaskRegression([string]$Name,[string]$Tests,[string]$ReportClass){
    $taskLog=Join-Path $taskLogs ($Name+'.log')
    Write-Host "Running $Name regression against isolated fixtures..."
    Push-Location (Join-Path $taskRoot 'backend')
    $taskSavedErrorPreference=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue'
        & $taskMaven @taskArgs "-Dtest=$Tests" test *> $taskLog
        $taskCode=$LASTEXITCODE
    } finally {$ErrorActionPreference=$taskSavedErrorPreference;Pop-Location}
    if($taskCode -ne 0){Get-Content -LiteralPath $taskLog -Tail 50;throw "$Name regression failed; see $taskLog"}
    $taskReport=Join-Path $taskRoot "backend\target\surefire-reports\TEST-cn.smartserve.$ReportClass.xml"
    [xml]$taskXml=Get-Content -LiteralPath $taskReport -Raw
    $taskSuite=$taskXml.testsuite
    $taskResults.Add([ordered]@{mode=$Name;tests=[int]$taskSuite.tests;failures=[int]$taskSuite.failures;errors=[int]$taskSuite.errors;skipped=[int]$taskSuite.skipped;database=$env:SMARTSERVE_PARITY_DB_URL;log=".local/verification/$Name.log"})
    Write-Host "$Name passed."
}
try {
    foreach($taskKey in @('DB_USER','DB_PASSWORD','REDIS_PASSWORD','RABBIT_USER','RABBIT_PASSWORD')){[Environment]::SetEnvironmentVariable($taskKey,$taskConfig[$taskKey],'Process')}
    $env:SMARTSERVE_PARITY_TEST='true'
    $taskRun=[DateTime]::UtcNow.ToString('yyyyMMddHHmmss')+'_'+[guid]::NewGuid().ToString('N').Substring(0,8)
    foreach($taskMode in @(@('native','5432','false'),@('docker','15432','true'))){
        $env:SMARTSERVE_PARITY_DB_URL='jdbc:postgresql://127.0.0.1:'+$taskMode[1]+'/smartserve_verify_'+$taskMode[0]+'_'+$taskRun
        $env:SMARTSERVE_PARITY_PGVECTOR=$taskMode[2]
        Invoke-TaskRegression $taskMode[0] 'DatabaseParityIntegrationTest' 'DatabaseParityIntegrationTest'
    }
    $env:SMARTSERVE_STANDARD_TEST='true'
    $env:DB_URL=$env:SMARTSERVE_PARITY_DB_URL
    $env:SMARTSERVE_TEST_QUEUE='smartserve.verify.'+$taskRun
    $env:REDIS_HOST='127.0.0.1';$env:REDIS_PORT='16379'
    $env:RABBIT_HOST='127.0.0.1';$env:RABBIT_PORT='5672'
    Invoke-TaskRegression 'docker-infrastructure' 'StandardStackIntegrationTest' 'StandardStackIntegrationTest'
    $taskOutput=Join-Path $taskRoot 'data\evaluation\results\business-modes.json'
    $taskPayload=[ordered]@{measuredAt=[DateTime]::UtcNow.ToString('o');scope='Identical commerce/permission/Agent tests on isolated native PG and Docker PG, plus separate Redis/RabbitMQ verification';results=$taskResults;notes=@('Verification databases use smartserve_verify_* names and are retained for inspection. Business databases and user orders are not reset.','No paid model calls; this is correctness regression, not load or production acceptance testing.')}
    [IO.File]::WriteAllText($taskOutput,($taskPayload | ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
    Write-Host "Report: $taskOutput"
} finally {
    foreach($taskKey in $taskPrevious.Keys){[Environment]::SetEnvironmentVariable($taskKey,$taskPrevious[$taskKey],'Process')}
}
