param([scriptblock]$BeforeStart)
$ErrorActionPreference = 'Stop'
$llRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeRoot = [IO.Path]::GetFullPath((Join-Path $llRoot '../.codex-workspace/verification/ll/runtime'))
$beRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../CatPjt-TranslaCat-be'))

# 준비: loopback 테스트 서버와 격리 DB overlay만 확인하고 자격증명은 메모리에 유지한다.
$listeners = @(Get-NetTCPConnection -LocalPort 18767 -State Listen -ErrorAction SilentlyContinue)
$owners = @($listeners.OwningProcess | Select-Object -Unique)
if ($owners.Count -ne 1) { throw 'Expected one owned BE listener' }
$process = Get-CimInstance Win32_Process -Filter "ProcessId=$($owners[0])"
if ($process.Name -ne 'java.exe' -or $process.CommandLine -notlike '*spring-boot-translacat-0.0.1-SNAPSHOT.jar*' -or
    $process.CommandLine -notlike '*live-application.properties*') { throw 'Refusing unrelated process restart' }
$overlay = Get-Content (Join-Path $beRoot 'build/live-application.properties')
if (@($overlay | Where-Object { $_ -eq 'server.port=18767' }).Count -ne 1 -or
    @($overlay | Where-Object { $_ -like 'spring.datasource.url=jdbc:log4jdbc:mysql://127.0.0.1:33316/translacat_be_it_live_7cb72f72?*' }).Count -ne 1) {
    throw 'Expected owned scratch BE overlay'
}
$entry = docker inspect --format '{{json .Config.Env}}' 9d6e91ffcea2 | ConvertFrom-Json |
    Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' } | Select-Object -First 1
if (-not $entry) { throw 'Scratch database unavailable' }
$env:TEST_DB_PASSWORD = $entry.Substring('MYSQL_ROOT_PASSWORD='.Length)
$env:TEST_LL_KEY = [Convert]::ToBase64String([byte[]](0..31))
$env:TEST_JWT_KEY = [Convert]::ToBase64String([byte[]](0..63))
$prefix = if ($process.CommandLine.StartsWith('"')) { '"' + $process.ExecutablePath + '"' } else { $process.ExecutablePath }
if (-not $process.CommandLine.StartsWith($prefix)) { throw 'Unexpected Java command prefix' }
$arguments = $process.CommandLine.Substring($prefix.Length).Trim()

# 준비: 중앙 검증 로그 디렉터리를 프로세스 중지 전에 확보한다.
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null

# 실행: 이 작업의 BE PID만 교체하며 기존 DB와 다른 JVM은 유지한다.
Stop-Process -Id $process.ProcessId
if ($BeforeStart) { & $BeforeStart }

$stamp = [guid]::NewGuid().ToString('N')
$started = Start-Process -FilePath $process.ExecutablePath -ArgumentList $arguments -WorkingDirectory $beRoot -WindowStyle Hidden `
    -RedirectStandardOutput "$runtimeRoot/be-$stamp.out.log" `
    -RedirectStandardError "$runtimeRoot/be-$stamp.err.log" -PassThru

# 검증: 인증이 필요한 로컬 설정 경로로 준비 상태를 확인한다.
$ready = $false
$deadline = (Get-Date).AddSeconds(50)
while (-not $ready -and (Get-Date) -lt $deadline) {
    try { $ready = (Invoke-WebRequest 'http://127.0.0.1:18767/api/v1/language-learning/settings' -SkipHttpErrorCheck -TimeoutSec 2).StatusCode -eq 401 }
    catch { Start-Sleep -Milliseconds 300 }
}
if (-not $ready) { throw "BE startup failed; inspect local log be-$stamp.err.log" }
Write-Output "BE ready: PID=$($started.Id), scratch MySQL and LL loopback only"
