param([scriptblock]$BeforeStart, [switch]$RefreshClasspath)
$ErrorActionPreference = 'Stop'
$llRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$catalog = 'translacat_ll_it_live_7cb72f72'

# 준비: 이 작업의 격리 Ktor 프로세스만 확인하고 기존 실행 인자를 그대로 사용한다.
$processes = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'java.exe' -and $_.CommandLine -like '*io.ktor.server.netty.EngineMain -config=build/live-application.yaml*' -and
    $_.CommandLine.Contains($llRoot)
})
if ($processes.Count -ne 1) { throw 'Expected exactly one owned local Ktor process' }
$process = $processes[0]
$executablePrefix = if ($process.CommandLine.StartsWith('"')) { '"' + $process.ExecutablePath + '"' } else { $process.ExecutablePath }
if (-not $process.CommandLine.StartsWith($executablePrefix)) { throw 'Unexpected Java command prefix' }
$arguments = $process.CommandLine.Substring($executablePrefix.Length).Trim()
if ($RefreshClasspath) {
    # 명시적 Gradle 산출물로만 의존성을 갱신하며 JVM 옵션과 테스트 설정 경로는 유지한다.
    $classpathFile = Join-Path $llRoot 'build/local-runtime-classpath.txt'
    $classpath = (Get-Content -Raw -LiteralPath $classpathFile).Trim()
    if (-not $classpath.StartsWith($llRoot) -or $classpath.Contains('"') -or $classpath.Contains("`n")) {
        throw 'Invalid local runtime classpath'
    }
    $matched = [regex]::Match($arguments, '(?<prefix>^.*?)(?:-cp|-classpath)\s+(?:"[^"]+"|\S+)\s+(?<main>io\.ktor\.server\.netty\.EngineMain -config=build/live-application\.yaml.*)$')
    if (-not $matched.Success) { throw 'Unexpected Ktor classpath arguments' }
    $arguments = $matched.Groups['prefix'].Value + '-cp "' + $classpath + '" ' + $matched.Groups['main'].Value
    if ($arguments.Length -gt 32000) { throw 'Local runtime classpath exceeds Windows process limit' }
}
$entry = docker inspect --format '{{json .Config.Env}}' 9d6e91ffcea2 | ConvertFrom-Json |
    Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' } | Select-Object -First 1
if (-not $entry -or $catalog -notmatch '^translacat_ll_it_live_[0-9a-f]{8}$') { throw 'Scratch database unavailable' }
$env:DB_PASSWORD = $entry.Substring('MYSQL_ROOT_PASSWORD='.Length)
$env:DB_USERNAME = 'root'
$env:DB_JDBC_URL = "jdbc:mysql://127.0.0.1:33316/$catalog"
$env:DB_EXPECTED_CATALOG = $catalog
$env:LL_INTERNAL_JWT_SECRET_BASE64 = [Convert]::ToBase64String([byte[]](0..31))
$env:AI_SERVER_URL = 'http://127.0.0.1:18765'
$env:AI_SERVER_API_KEY = 'synthetic-local-model-key'

# 실행: 확인한 애플리케이션만 재시작하며 DB 데이터와 Gradle 프로세스는 보존한다.
Stop-Process -Id $process.ProcessId
if ($BeforeStart) { & $BeforeStart }
$stamp = [guid]::NewGuid().ToString('N')
$started = Start-Process -FilePath $process.ExecutablePath -ArgumentList $arguments -WorkingDirectory $llRoot -WindowStyle Hidden `
    -RedirectStandardOutput "$llRoot/.tmp_ktor_m0/ktor-$stamp.out.log" `
    -RedirectStandardError "$llRoot/.tmp_ktor_m0/ktor-$stamp.err.log" -PassThru

# 검증: 인증 없는 로컬 요청의 401로 애플리케이션 준비를 확인한다.
$ready = $false
$deadline = (Get-Date).AddSeconds(25)
while (-not $ready -and (Get-Date) -lt $deadline) {
    try {
        $response = Invoke-WebRequest 'http://127.0.0.1:18766/internal/v1/language-learning/writing/daily/report' -SkipHttpErrorCheck -TimeoutSec 2
        $ready = $response.StatusCode -eq 401
    } catch { Start-Sleep -Milliseconds 300 }
}
if (-not $ready) { throw "Ktor startup failed; inspect sanitized local log ktor-$stamp.err.log" }
Write-Output "Ktor ready: PID=$($started.Id), scratch database, Python loopback only"
