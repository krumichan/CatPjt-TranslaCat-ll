param([switch]$Browser)
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18767/api/v1'
$container = '9d6e91ffcea2'
$coreCatalog = 'translacat_be_it_live_7cb72f72'
$llCatalog = 'translacat_ll_it_live_7cb72f72'
$started = Get-Date
$script:requests = 0
if ($coreCatalog -notmatch '^translacat_be_it_live_[0-9a-f]{8}$' -or $llCatalog -notmatch '^translacat_ll_it_live_[0-9a-f]{8}$') {
    throw 'Only owned scratch catalogs are permitted'
}

function Invoke-AdminTestApi($method, $path, $payload, $headers = @{}, $expected = 200) {
    $arguments = @{Uri="$base$path"; Method=$method; Headers=$headers; SkipHttpErrorCheck=$true; TimeoutSec=15}
    if ($null -ne $payload) {
        $arguments.ContentType = 'application/json'
        $arguments.Body = $payload | ConvertTo-Json -Depth 12 -Compress
    }
    $response = Invoke-WebRequest @arguments
    $script:requests++
    if ([int]$response.StatusCode -ne $expected) {
        $errorCode = try { ($response.Content | ConvertFrom-Json).body.errorCode } catch { 'UNREADABLE' }
        if ($errorCode -notmatch '^[A-Za-z0-9_]{1,80}$') { $errorCode = 'UNCLASSIFIED' }
        $diagnosticTypes = try {
            [regex]::Matches(($response.Content | ConvertFrom-Json).message, '\b[A-Za-z][A-Za-z0-9]*(?:Exception|Error|Dto)\b').Value | Select-Object -Unique
        } catch { @() }
        throw "Admin verification status=$($response.StatusCode) code=$errorCode types=$($diagnosticTypes -join ',') method=$method path=$path"
    }
    if ($response.Content) { return ($response.Content | ConvertFrom-Json).body }
}

function Invoke-ScratchSql($catalog, $sql) {
    if ($catalog -notin @($coreCatalog, $llCatalog)) { throw 'Unowned catalog' }
    $result = docker exec -e "MYSQL_PWD=$dbPassword" $container mysql -uroot $catalog --batch --skip-column-names -e $sql
    if ($LASTEXITCODE -ne 0) { throw 'Scratch SQL failed' }
    return $result
}

# 준비: 실제 인증 API로 만든 합성 계정 한 개만 관리자로 바꾼다. 자격증명은 메모리에만 둔다.
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
$email = "ad-$suffix@example.test"
$password = 'Synthetic-' + [guid]::NewGuid().ToString('N')
$registered = Invoke-AdminTestApi POST '/auth/register' @{email=$email; password=$password; username='Synthetic Admin'}
[long]$adminId = $registered.id
if ($adminId -le 0) { throw 'Missing synthetic user ID' }
$ordinary = Invoke-AdminTestApi POST '/auth/login' @{email=$email; password=$password}
$ordinaryHeaders = @{Authorization="Bearer $($ordinary.accessToken)"}
$null = Invoke-AdminTestApi GET '/language-learning/settings' $null $ordinaryHeaders
$null = Invoke-AdminTestApi GET '/admin/language-learning/settings' $null $ordinaryHeaders 403
$topics = @(Invoke-AdminTestApi GET '/language-learning/speaking/topics' $null $ordinaryHeaders)
if ($topics.Count -eq 0) { throw 'Speaking topic catalog missing' }
$topic = $topics[0]
$null = Invoke-AdminTestApi PATCH "/admin/language-learning/speaking/topics/$($topic.id)" @{} $ordinaryHeaders 403
$entry = docker inspect --format '{{json .Config.Env}}' $container | ConvertFrom-Json | Where-Object {$_ -like 'MYSQL_ROOT_PASSWORD=*'} | Select-Object -First 1
$dbPassword = $entry.Substring('MYSQL_ROOT_PASSWORD='.Length)
$changed = Invoke-ScratchSql $coreCatalog "UPDATE user SET authority='ADMIN' WHERE id=$adminId AND email='$email' AND authority='USER'; SELECT ROW_COUNT();"
if ([int]$changed -ne 1) { throw 'Synthetic role change did not affect exactly one owned account' }
$login = Invoke-AdminTestApi POST '/auth/login' @{email=$email; password=$password}
$headers = @{Authorization="Bearer $($login.accessToken)"}

# 실행: 설정 값은 그대로 저장하고 신규 테스트 keyword만 생성·변경한다.
$topicSaved = Invoke-AdminTestApi PATCH "/admin/language-learning/speaking/topics/$($topic.id)" @{
    title=$topic.title; description=$topic.description; recommendedLevel=$topic.recommendedLevel
    recommendedStartMode=$topic.recommendedStartMode; sortOrder=$topic.sortOrder
} $headers
if (($topic | ConvertTo-Json -Depth 10 -Compress) -ne ($topicSaved | ConvertTo-Json -Depth 10 -Compress)) {
    throw 'Unchanged Speaking topic patch changed catalog values'
}
$settings = Invoke-AdminTestApi GET '/admin/language-learning/settings' $null $headers
$saved = Invoke-AdminTestApi PATCH '/admin/language-learning/settings' @{defaultDailySentenceCount=$settings.defaultDailySentenceCount} $headers
if (($settings | ConvertTo-Json -Depth 10 -Compress) -ne ($saved | ConvertTo-Json -Depth 10 -Compress)) { throw 'Unchanged settings patch changed policy values' }
$malformed = Invoke-AdminTestApi POST '/admin/language-learning/system-keywords' @{text="Synthetic invalid $suffix"; type='WORD'} $headers 400
if ($malformed.errorCode -ne 'LANGUAGE_LEARNING_REQUEST_INVALID') { throw 'Malformed enum error contract changed' }
$null = Invoke-AdminTestApi POST '/admin/language-learning/system-keywords' @{text="Synthetic invalid $suffix"; type='VOCABULARY'} $headers 400
$system = Invoke-AdminTestApi POST '/admin/language-learning/system-keywords' @{text="Synthetic catalog $suffix"; type='TOPIC'; canonicalKey="synthetic-$suffix"; sortOrder=999} $headers
$inactive = Invoke-AdminTestApi PATCH "/admin/language-learning/system-keywords/$($system.id)" @{active=$false} $headers
if ($inactive.active) { throw 'Synthetic system keyword remained active' }
$all = Invoke-AdminTestApi GET '/admin/language-learning/system-keywords' $null $headers
if (@($all | Where-Object {$_.id -eq $system.id -and -not $_.active}).Count -ne 1) { throw 'Admin catalog did not retain update' }
$null = Invoke-AdminTestApi PATCH '/language-learning/settings' @{originLanguage='ko'; learningLanguage='en'; timezone='Asia/Seoul'; dailySentenceCount=1} $headers
$custom = Invoke-AdminTestApi POST '/language-learning/keywords/custom' @{text="Synthetic own $suffix"; type='VOCABULARY'} $headers
$updated = Invoke-AdminTestApi PATCH "/language-learning/keywords/custom/$($custom.id)" @{text="Synthetic revised $suffix"} $headers
if ($updated.text -ne "Synthetic revised $suffix") { throw 'Custom keyword update was lost' }

# 검증: 다른 합성 사용자의 수정은 거부하며 감사 기록과 catalog가 LL DB에 기록되는지 확인한다.
$secondPassword = 'Synthetic-' + [guid]::NewGuid().ToString('N')
$secondEmail = "au-$suffix@example.test"
$null = Invoke-AdminTestApi POST '/auth/register' @{email=$secondEmail; password=$secondPassword; username='Synthetic Other'}
$second = Invoke-AdminTestApi POST '/auth/login' @{email=$secondEmail; password=$secondPassword}
$secondHeaders = @{Authorization="Bearer $($second.accessToken)"}
$null = Invoke-AdminTestApi PATCH "/language-learning/keywords/custom/$($custom.id)" @{text='Foreign change'} $secondHeaders 400
$null = Invoke-AdminTestApi DELETE "/language-learning/keywords/custom/$($custom.id)" $null $headers
$counts = Invoke-ScratchSql $llCatalog "SELECT COUNT(*) FROM language_learning_admin_setting_audit WHERE admin_user_id=$adminId; SELECT COUNT(*) FROM language_learning_system_keyword WHERE id=$($system.id) AND canonical_key='synthetic-$suffix' AND active=0;"
if ([int]$counts[0] -lt 1 -or [int]$counts[1] -ne 1) { throw 'Admin changes did not reach LL MySQL' }

if ($Browser) {
    $env:E2E_ADMIN_ACCESS_TOKEN = $login.accessToken
    $env:E2E_ADMIN_REFRESH_TOKEN = $login.refreshToken
    $env:E2E_ADMIN_PUBLIC_ID = $login.publicId
    $env:NEXTAUTH_SECRET = 'synthetic-cutover-nextauth-secret-20260926'
    $env:NEXTAUTH_URL = 'http://localhost:3000'
    $env:NEXT_PUBLIC_API_URL = $base
    $env:E2E_API_BASE_URL = $base
    $env:E2E_BASE_URL = 'http://localhost:3000'
    Push-Location (Join-Path $PSScriptRoot '../../CatPjt-TranslaCat-fe')
    try {
        & ./node_modules/.bin/playwright.cmd test e2e/integration/admin-learning-cutover.spec.ts --project=integration-chromium --reporter=list
        if ($LASTEXITCODE -ne 0) { throw 'Admin UI verification failed' }
    } finally { Pop-Location }
}
Write-Output "PASS: actual admin/user authorization, Settings/Keyword HTTP=$script:requests, LL MySQL audit, paidCalls=0, elapsedSeconds=$([math]::Round(((Get-Date)-$started).TotalSeconds,2))"
