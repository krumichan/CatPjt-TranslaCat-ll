param([ValidateSet('LL', 'BE')][string]$Service)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$beRoot = [IO.Path]::GetFullPath((Join-Path $root '../CatPjt-TranslaCat-be'))
$evidence = [IO.Path]::GetFullPath((Join-Path $root '../.codex-workspace/verification/ll/final-cleanup'))
$java = 'C:/Users/lovel/.jdks/corretto-21.0.3/bin/java.exe'
$port = if ($Service -eq 'LL') { 18766 } else { 18767 }

# 준비: 이미 확인한 격리 DB와 테스트 overlay만 사용하고 일반 프로세스는 교체하지 않는다.
if (-not $Service -or -not (Test-Path -LiteralPath $java)) { throw 'Explicit service and existing JDK required' }
if (@(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue).Count -ne 0) {
    throw 'Service port already in use; do not replace an unknown process'
}
$details = docker inspect 9d6e91ffcea2 | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $details.Config.Image -ne 'mysql:8.4' -or
    $details.NetworkSettings.Ports.'3306/tcp'[0].HostIp -ne '127.0.0.1' -or
    $details.NetworkSettings.Ports.'3306/tcp'[0].HostPort -ne '33316') { throw 'Owned scratch MySQL unavailable' }
$entry = @($details.Config.Env | Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' })
if ($entry.Count -ne 1) { throw 'Missing scratch credential' }
$password = $entry[0].Substring('MYSQL_ROOT_PASSWORD='.Length)
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $evidence -Force | Out-Null

if ($Service -eq 'LL') {
    $overlay = Get-Content -LiteralPath (Join-Path $root 'build/live-application.yaml') -Raw
    if ($overlay -notmatch '18766' -or -not $overlay.Contains('jdbcUrl: ${DB_JDBC_URL}') -or
        -not $overlay.Contains('expectedCatalog: ${DB_EXPECTED_CATALOG}') -or -not $overlay.Contains('url: ${AI_SERVER_URL}')) {
        throw 'Expected owned LL overlay'
    }
    $classpath = (Get-Content -LiteralPath (Join-Path $root 'build/local-runtime-classpath.txt') -Raw).Trim()
    if (-not $classpath.Replace('\', '/').StartsWith($root.Replace('\', '/'), [StringComparison]::OrdinalIgnoreCase) -or
        $classpath.Contains('"') -or $classpath.Contains("`n")) {
        throw 'Unexpected LL runtime classpath'
    }
    $env:DB_USERNAME = 'root'
    $env:DB_PASSWORD = $password
    $env:DB_JDBC_URL = 'jdbc:mysql://127.0.0.1:33316/translacat_ll_it_live_7cb72f72'
    $env:DB_EXPECTED_CATALOG = 'translacat_ll_it_live_7cb72f72'
    $env:LL_INTERNAL_JWT_SECRET_BASE64 = [Convert]::ToBase64String([byte[]](0..31))
    $env:AI_SERVER_URL = 'http://127.0.0.1:18765'
    $env:AI_SERVER_API_KEY = 'synthetic-local-model-key'
    $arguments = '-Xms128m -Xmx512m -cp "' + $classpath + '" io.ktor.server.netty.EngineMain -config=build/live-application.yaml'
    $directory = $root
    $probe = 'http://127.0.0.1:18766/internal/v1/language-learning/writing/daily/report'
} else {
    $overlay = Get-Content -LiteralPath (Join-Path $beRoot 'build/live-application.properties')
    if (@($overlay | Where-Object { $_ -eq 'server.port=18767' }).Count -ne 1 -or
        @($overlay | Where-Object { $_ -like 'spring.datasource.url=jdbc:log4jdbc:mysql://127.0.0.1:33316/translacat_be_it_live_7cb72f72?*' }).Count -ne 1) {
        throw 'Expected owned BE overlay'
    }
    $env:TEST_DB_PASSWORD = $password
    $env:TEST_LL_KEY = [Convert]::ToBase64String([byte[]](0..31))
    $env:TEST_JWT_KEY = [Convert]::ToBase64String([byte[]](0..63))
    $arguments = '-Xms128m -Xmx512m -jar build/libs/spring-boot-translacat-0.0.1-SNAPSHOT.jar --spring.config.additional-location=file:build/live-application.properties'
    $directory = $beRoot
    $probe = 'http://127.0.0.1:18767/api/v1/language-learning/settings'
}

# 실행: 필요한 자격증명은 프로세스 환경에만 전달하고 새 창을 열지 않는다.
$stamp = [guid]::NewGuid().ToString('N')
$started = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $directory -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $evidence "$Service-$stamp.out.log") `
    -RedirectStandardError (Join-Path $evidence "$Service-$stamp.err.log") -PassThru

# 검증: 인증 없는 요청이 거부되는 실제 서버 준비 상태만 확인한다.
$ready = $false
$deadline = (Get-Date).AddSeconds(50)
while (-not $ready -and (Get-Date) -lt $deadline) {
    try { $ready = (Invoke-WebRequest $probe -SkipHttpErrorCheck -TimeoutSec 2).StatusCode -eq 401 }
    catch { Start-Sleep -Milliseconds 300 }
}
if (-not $ready) { throw "Owned $Service startup failed; inspect sanitized $Service-$stamp logs" }
[IO.File]::WriteAllText((Join-Path $evidence "$Service-runtime.json"), (@{service=$Service;pid=$started.Id;port=$port;
    stdout="$Service-$stamp.out.log";stderr="$Service-$stamp.err.log"} | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
Write-Output "$Service ready: PID=$($started.Id), port=$port, owned scratch database"
