param([ValidateSet('Http', 'Ui')][string]$Track, [string]$StartAt)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$aiRoot = [IO.Path]::GetFullPath((Join-Path $root '../CatPjt-TranslaCat-ai'))
$evidence = Join-Path $root '.tmp_ktor_final_cleanup'
$results = [Collections.Generic.List[object]]::new()
$script:reachedStart = [string]::IsNullOrEmpty($StartAt)
if (-not $script:reachedStart -and (Test-Path -LiteralPath (Join-Path $evidence "$Track-results.json"))) {
    foreach ($previous in @(Get-Content -LiteralPath (Join-Path $evidence "$Track-results.json") -Raw | ConvertFrom-Json)) {
        if ($previous.name -eq $StartAt) { break }
        $results.Add($previous)
    }
}

function Invoke-VerificationStep([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$Directory) {
    if (-not $script:reachedStart) {
        if ($Name -ne $StartAt) { return }
        $script:reachedStart = $true
    }
    # 준비: 단계별 원본 로그와 종료 코드를 보존하고 합성 Provider 계수만 읽는다.
    if ($Track -eq 'Ui') {
        $deadline = (Get-Date).AddSeconds(5)
        do {
            $listeners = @(Get-NetTCPConnection -LocalPort 3000 -State Listen -ErrorAction SilentlyContinue)
            if ($listeners.Count -eq 0) { break }
            Start-Sleep -Milliseconds 250
        } while ((Get-Date) -lt $deadline)
        if ($listeners.Count -gt 0) { throw 'FE test port is occupied; review ownership before reusing a server' }
    }
    $statsFile = Join-Path $aiRoot '.tmp_ktor_m0/synthetic_execution_stats.json'
    $before = Get-Content -LiteralPath $statsFile -Raw | ConvertFrom-Json
    $started = Get-Date
    $log = Join-Path $evidence "$Name.log"
    Write-Output "START $Name"

    # 실행: 재기동한 서버는 계속 살아 있어야 하므로 검사 프로세스 자체의 종료만 기다린다.
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $Directory `
        -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru
    while (-not $process.HasExited) { Start-Sleep -Milliseconds 200; $process.Refresh() }
    $code = $process.ExitCode
    if ($null -eq $code) { throw "Verification process exit code unavailable: $Name" }

    # 검증: 실패 단계도 기록하며 실제 실패를 다음 단계의 성공으로 덮어쓰지 않는다.
    $after = Get-Content -LiteralPath $statsFile -Raw | ConvertFrom-Json
    $sameCounter = $after.processId -eq $before.processId -and $after.modelCalls -ge $before.modelCalls
    $connections = foreach ($port in @(18766, 18767)) {
        $listener = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
        foreach ($ownerId in @($listener.OwningProcess | Select-Object -Unique)) {
            Get-NetTCPConnection -OwningProcess $ownerId -State Established -ErrorAction SilentlyContinue |
                Select-Object @{Name='servicePort'; Expression={ $port }}, LocalPort, RemoteAddress, RemotePort
        }
    }
    $result = [pscustomobject]@{
        name = $Name; executable = $Executable; arguments = $Arguments; exitCode = $code
        elapsedSeconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 2)
        modelCalls = if ($sameCounter) { $after.modelCalls - $before.modelCalls } else { $null }
        counterVerified = $sameCounter
        paidCalls = $after.paidCalls - $before.paidCalls
        connectionsAfterStep = @($connections)
        log = $log
        errorLog = "$log.err"
    }
    $results.Add($result)
    [IO.File]::WriteAllText((Join-Path $evidence "$Track-results.json"),
        (ConvertTo-Json -InputObject @($results.ToArray()) -Depth 4), [Text.UTF8Encoding]::new($false))
    Write-Output "END $Name exit=$code elapsed=$($result.elapsedSeconds)s syntheticModels=$($result.modelCalls) paid=$($result.paidCalls)"
    if ($code -ne 0) { throw "Verification failed: $Name; inspect its local log" }
    if (-not $sameCounter) { throw 'Synthetic Provider counter changed process; call count requires reconciliation' }
    if ($result.paidCalls -ne 0) { throw 'Unexpected paid Provider call' }
}

function Invoke-LearningCheck([string]$Name, [string]$Script, [string[]]$Options = @()) {
    Invoke-VerificationStep $Name 'pwsh' (@('-NoProfile', '-File', "scripts/$Script") + $Options) $root
}

if ($Track -eq 'Http') {
    Invoke-LearningCheck 'writing-http' 'verify-writing-cutover.ps1' @('-AllModes')
    Invoke-LearningCheck 'writing-restart' 'verify-writing-cutover.ps1' @('-CrashRecovery')
    Invoke-LearningCheck 'practice-http' 'verify-practice-cutover.ps1'
    Invoke-LearningCheck 'practice-restart' 'verify-practice-cutover.ps1' @('-CrashRecovery')
    Invoke-LearningCheck 'vocabulary-http' 'verify-practice-cutover.ps1' @('-Vocabulary')
    Invoke-LearningCheck 'speaking-http' 'verify-speaking-cutover.ps1'
    Invoke-LearningCheck 'speaking-failures' 'verify-speaking-cutover.ps1' @('-Failures')
    Invoke-LearningCheck 'speaking-read-aloud' 'verify-speaking-cutover.ps1' @('-ReadAloud')
    Invoke-LearningCheck 'speaking-restart' 'verify-speaking-cutover.ps1' @('-CrashRecovery')
    Invoke-LearningCheck 'keywords-http' 'verify-speaking-cutover.ps1' @('-KeywordScheduling')
    Invoke-VerificationStep 'listening-http' "$aiRoot/.venv/Scripts/python.exe" @('-m', 'scripts.verify_listening_http', '--via-be') $aiRoot
    Invoke-VerificationStep 'listening-failures' "$aiRoot/.venv/Scripts/python.exe" @('-m', 'scripts.verify_listening_failure_http') $aiRoot
    Invoke-LearningCheck 'listening-restart' 'verify-listening-crash.ps1'
    Invoke-LearningCheck 'admin-http' 'verify-admin-cutover.ps1'
    Invoke-VerificationStep 'overview-current-http' "$aiRoot/.venv/Scripts/python.exe" @('-m', 'scripts.verify_overview_http', '--current', '--boundaries') $aiRoot
} elseif ($Track -eq 'Ui') {
    Invoke-LearningCheck 'level-test-ui' 'verify-leveltest-cutover.ps1'
    Invoke-LearningCheck 'writing-ui' 'verify-writing-cutover.ps1' @('-Browser')
    Invoke-LearningCheck 'practice-ui' 'verify-practice-cutover.ps1' @('-Browser')
    Invoke-LearningCheck 'vocabulary-ui' 'verify-practice-cutover.ps1' @('-Vocabulary', '-Browser')
    Invoke-LearningCheck 'speaking-ui' 'verify-speaking-cutover.ps1' @('-Browser')
    Invoke-LearningCheck 'listening-ui' 'verify-listening-ui.ps1'
    Invoke-LearningCheck 'admin-ui' 'verify-admin-cutover.ps1' @('-Browser')
    Invoke-VerificationStep 'overview-ui' "$aiRoot/.venv/Scripts/python.exe" @('-m', 'scripts.verify_overview_ui') $aiRoot
} else { throw 'Explicit verification track required' }
if (-not $script:reachedStart) { throw 'Unknown starting verification step' }
