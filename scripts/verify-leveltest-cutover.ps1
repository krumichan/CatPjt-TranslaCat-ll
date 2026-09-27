$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18767/api/v1'
$started = Get-Date

function Invoke-LevelTestApi($method, $path, $payload, $headers = @{}) {
    $arguments = @{Uri="$base$path"; Method=$method; Headers=$headers; SkipHttpErrorCheck=$true; TimeoutSec=15}
    if ($null -ne $payload) {
        $arguments.ContentType = 'application/json'
        $arguments.Body = $payload | ConvertTo-Json -Depth 10 -Compress
    }
    $response = Invoke-WebRequest @arguments
    if ([int]$response.StatusCode -ne 200) { throw "Synthetic Level API status=$($response.StatusCode) path=$path" }
    return ($response.Content | ConvertFrom-Json).body
}

# 준비: 기존 데이터는 건드리지 않고 실제 인증 API로 합성 계정을 등록한다.
$identity = [guid]::NewGuid().ToString('N')
$email = "level-$identity@example.test"
$password = 'Synthetic-' + [guid]::NewGuid().ToString('N')
$registered = Invoke-LevelTestApi POST '/auth/register' @{email=$email; password=$password; username='Synthetic Level'}
$login = Invoke-LevelTestApi POST '/auth/login' @{email=$email; password=$password}
$headers = @{Authorization="Bearer $($login.accessToken)"}
$null = Invoke-LevelTestApi PATCH '/language-learning/settings' @{originLanguage='ko'; learningLanguage='en'; timezone='Asia/Seoul'; dailySentenceCount=1} $headers
$env:E2E_LEVEL_ACCESS_TOKEN = $login.accessToken
$env:E2E_LEVEL_REFRESH_TOKEN = $login.refreshToken
$env:E2E_LEVEL_PUBLIC_ID = $login.publicId
$env:NEXTAUTH_SECRET = 'synthetic-cutover-nextauth-secret-20260926'
$env:NEXTAUTH_URL = 'http://localhost:3000'
$env:NEXT_PUBLIC_API_URL = $base
$env:E2E_API_BASE_URL = $base
$env:E2E_BASE_URL = 'http://localhost:3000'

# 실행: 업무 경로를 우회하지 않는 실제 브라우저 검사를 실행한다.
Push-Location (Join-Path $PSScriptRoot '../../CatPjt-TranslaCat-fe')
try {
    & .\node_modules\.bin\playwright.cmd test e2e/integration/level-test-cutover.spec.ts --project=integration-chromium --reporter=list
    if ($LASTEXITCODE -ne 0) { throw 'Level Test browser verification failed' }
} finally { Pop-Location }

# 검증: UI 완료 뒤 BE가 LL의 완료 프로필을 다시 조회하는지 확인한다.
$status = Invoke-LevelTestApi GET '/language-learning/level-test/status' $null $headers
if (-not $status.initialLevelTestCompleted -or $status.activeSessionId) { throw 'Level completion profile was not retained' }
Write-Output "PASS: actual FE/BE/LL/Python generic HTTP/MySQL 20 questions; paid model calls=0; elapsed=$([math]::Round(((Get-Date)-$started).TotalSeconds,2))s"
