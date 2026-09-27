param([switch]$Browser, [switch]$AllModes, [switch]$CrashRecovery)
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18767/api/v1'
$container = '9d6e91ffcea2'
$catalog = 'translacat_ll_it_live_7cb72f72'
if ($catalog -notmatch '^translacat_ll_it_live_[0-9a-f]{8}$') { throw 'Scratch catalog required' }
$testRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../.codex-workspace/verification/ai/runtime'))
$control = Join-Path $testRoot 'writing-control.json'
$statsFile = Join-Path $testRoot 'synthetic_execution_stats.json'
if (Test-Path -LiteralPath $control) { throw 'Another Writing fixture control is active' }
if ($Browser -and $CrashRecovery) { throw 'Choose one verification track' }
$started = Get-Date
$beforeCalls = if (Test-Path -LiteralPath $statsFile) { (Get-Content -Raw $statsFile | ConvertFrom-Json).modelCalls } else { 0 }

# 서버 준비를 먼저 확인해 시작 중인 프로세스를 제품 실패로 해석하지 않는다.
$ready = $false
$startupDeadline = (Get-Date).AddSeconds(30)
while (-not $ready -and (Get-Date) -lt $startupDeadline) {
    try {
        $probe = Invoke-WebRequest 'http://127.0.0.1:18766/internal/v1/language-learning/writing/daily/report' -SkipHttpErrorCheck -TimeoutSec 2
        $ready = [int]$probe.StatusCode -eq 401
    } catch { $ready = $false }
    if (-not $ready) { Start-Sleep -Milliseconds 200 }
}
if (-not $ready) { throw 'Local Ktor server is not ready' }

function Invoke-TestApi($method, $path, $payload, $headers = @{}, $expected = 200, $expectedCode = $null) {
    $arguments = @{ Uri = "$base$path"; Method = $method; Headers = $headers; SkipHttpErrorCheck = $true }
    if ($null -ne $payload) {
        $arguments.ContentType = 'application/json'
        $arguments.Body = $payload | ConvertTo-Json -Depth 20 -Compress
    }
    $response = Invoke-WebRequest @arguments
    if ([int]$response.StatusCode -ne $expected) {
        throw "Synthetic API failed: $method $path status=$($response.StatusCode)"
    }
    if ($response.Content) {
        $body = ($response.Content | ConvertFrom-Json).body
        if ($null -ne $expectedCode -and $body.errorCode -ne $expectedCode) {
            throw "Synthetic API error code mismatch: $method $path"
        }
        return $body
    }
}

# 준비: 합성 계정만 생성하고 토큰·비밀번호는 프로세스 메모리에 유지한다.
$identity = [guid]::NewGuid().ToString('N')
$email = "cutover-$identity@example.test"
$password = 'Synthetic-' + [guid]::NewGuid().ToString('N')
$registered = Invoke-TestApi POST '/auth/register' @{email=$email; password=$password; username='Synthetic Cutover'}
$login = Invoke-TestApi POST '/auth/login' @{email=$email; password=$password}
$headers = @{Authorization = "Bearer $($login.accessToken)"}
[long]$userId = $registered.id
if ($userId -le 0) { throw 'Synthetic user missing' }
$sentenceCount = if ($AllModes -or $CrashRecovery) { 5 } else { 1 }
$null = Invoke-TestApi PATCH '/language-learning/settings' @{
    originLanguage='ko'; learningLanguage='en'; timezone='Asia/Seoul'; dailySentenceCount=$sentenceCount
} $headers

# 사전조건: 기존 레벨 테스트를 우회하는 제품 경로 대신 테스트 DB에 완료 기준점을 합성한다.
$entry = docker inspect --format '{{json .Config.Env}}' $container | ConvertFrom-Json |
    Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' } | Select-Object -First 1
$dbPassword = $entry.Substring('MYSQL_ROOT_PASSWORD='.Length)
$sql = @"
INSERT INTO language_learning_level_test_session (session_uid,user_id,session_type,status,origin_language,learning_language,timezone,current_question_number,current_complexity_band,base_level_score,proficiency_band,domain_scores_json,started_at,last_activity_at,completed_at,completed_date,idempotency_key)
VALUES(UUID(),$userId,'INITIAL','COMPLETED','ko','en','Asia/Seoul',20,3,60,'INTERMEDIATE','{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_DATE(),'synthetic-writing-prerequisite');
SET @session=LAST_INSERT_ID();
INSERT INTO language_learning_level_test_baseline(user_id,session_id,completion_id,session_type,base_level_score,proficiency_band,completed_date,started_at,completed_at)
VALUES($userId,@session,UUID(),'INITIAL',60,'INTERMEDIATE',UTC_DATE(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO language_learning_profile(user_id,profile_version,state,base_level_score,evaluation_count,confidence,trend,additional_signals_json,created_at,updated_at,created_by,updated_by)
VALUES($userId,'PROFILE','ACTIVE',60,0,0.0,'stable','{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'TEST','TEST')
ON DUPLICATE KEY UPDATE state='ACTIVE',base_level_score=60;
"@
docker exec -e "MYSQL_PWD=$dbPassword" $container mysql -uroot $catalog -e $sql
if ($LASTEXITCODE -ne 0) { throw 'Synthetic prerequisite insert failed' }

if ($CrashRecovery) {
    $marker = Join-Path $testRoot 'writing-hold.json'
    if (Test-Path -LiteralPath $marker) { throw 'Another Writing provider hold exists' }
    try {
        # 준비: 첫 문항 게시 뒤 두 번째 모델 HTTP만 지연하고 첫 답변 평가까지 완료한다.
        [IO.File]::WriteAllText($control, '{"writingType":"FREE","failure":"HOLD_AFTER_FIRST"}')
        $path = '/language-learning/writing/daily?writingType=FREE'
        $set = Invoke-TestApi GET $path $null $headers
        $deadline = (Get-Date).AddSeconds(25)
        do {
            $set = Invoke-TestApi GET $path $null $headers
            if ($set.status -eq 'GENERATING' -and $set.items.Count -eq 1 -and (Test-Path -LiteralPath $marker)) { break }
            Start-Sleep -Milliseconds 150
        } while ((Get-Date) -lt $deadline)
        if ($set.status -ne 'GENERATING' -or $set.items.Count -ne 1 -or -not (Test-Path -LiteralPath $marker)) { throw 'Writing in-flight model call was not reached' }
        $item = $set.items[0]
        $null = Invoke-TestApi POST "/language-learning/writing/daily/items/$($item.itemId)/answers" @{answer='Synthetic answer'; contentRevision=$item.contentRevision} $headers
        do {
            $set = Invoke-TestApi GET $path $null $headers
            if ($set.items[0].attempts[0].evaluationStatus -eq 'SUCCESS') { break }
            Start-Sleep -Milliseconds 150
        } while ((Get-Date) -lt $deadline)
        if ($set.items[0].attempts[0].evaluationStatus -ne 'SUCCESS') { throw 'Prior Writing answer was not evaluated before restart' }
        $originalItem = $set.items[0] | ConvertTo-Json -Depth 30 -Compress

        # 실행: 이 합성 세트의 lease 시각만 앞당긴 뒤 실제 OS 프로세스를 재시작한다.
        $localId = -[long]$set.dailySetId
        $sql = "UPDATE language_learning_daily_set SET generation_lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=$localId AND user_id=$userId AND status='GENERATING' AND generation_token IS NOT NULL; SELECT ROW_COUNT();"
        & (Join-Path $PSScriptRoot 'restart-local-ktor.ps1') -BeforeStart {
            # 종료 전에 lease를 만료시키면 기존 복구 worker가 먼저 회수할 수 있으므로 종료 후 시각을 조정한다.
            $changed = docker exec -e "MYSQL_PWD=$dbPassword" $container mysql -uroot $catalog --batch --skip-column-names -e $sql
            if ($LASTEXITCODE -ne 0 -or $changed.Trim() -ne '1') { throw 'Writing synthetic lease expiration did not match one owned job' }
            [IO.File]::WriteAllText($control, '{}')
        }

        # 검증: 재시도 API 없이 새 worker가 나머지 문항을 채우며 게시된 문항·답변·평가는 유지한다.
        $deadline = (Get-Date).AddSeconds(35)
        do {
            $set = Invoke-TestApi GET $path $null $headers
            if ($set.status -eq 'READY') { break }
            Start-Sleep -Milliseconds 150
        } while ((Get-Date) -lt $deadline)
        if ($set.status -ne 'READY' -or $set.items.Count -ne 5) { throw "Writing restart did not recover: status=$($set.status) count=$($set.items.Count) code=$($set.generationFailureMessage)" }
        if (($set.items[0] | ConvertTo-Json -Depth 30 -Compress) -ne $originalItem) { throw 'Writing restart changed prior item or answer' }
        $originalItems = $set.items | ConvertTo-Json -Depth 30 -Compress
        $regenerationCount = $set.regenerationCount
        [IO.File]::WriteAllText($control, '{"writingType":"FREE","failure":"REFUSE_REGENERATION"}')
        $null = Invoke-TestApi POST "/language-learning/writing/daily/$($set.dailySetId)/regenerate" $null $headers 422 'WRITING_GENERATION_VALIDATION_EXHAUSTED'
        $afterFailure = Invoke-TestApi GET $path $null $headers
        if (($afterFailure.items | ConvertTo-Json -Depth 30 -Compress) -ne $originalItems -or $afterFailure.regenerationCount -ne $regenerationCount) { throw 'Failed Writing regeneration changed existing data' }
        Write-Output 'PASS: Writing OS restart recovery, existing question/answer/evaluation preserved, failed regeneration kept all data; synthetic lease timestamp advanced; no retry API'
    } finally {
        if (Test-Path -LiteralPath $control) { Remove-Item -LiteralPath $control }
        if (Test-Path -LiteralPath $marker) { Remove-Item -LiteralPath $marker }
    }
} elseif ($Browser) {
    # 실행: 모델 출력만 합성인 실제 FE→BE→LL→Python HTTP 경로를 연다.
    $env:E2E_WRITING_ACCESS_TOKEN = $login.accessToken
    $env:E2E_WRITING_REFRESH_TOKEN = $login.refreshToken
    $env:E2E_WRITING_PUBLIC_ID = $login.publicId
    $env:E2E_WRITING_SENTENCE_COUNT = [string]$sentenceCount
    $env:E2E_WRITING_ALL_MODES = if ($AllModes) { '1' } else { '0' }
    $env:NEXTAUTH_SECRET = 'synthetic-cutover-nextauth-secret-20260926'
    $env:NEXTAUTH_URL = 'http://localhost:3000'
    $env:NEXT_PUBLIC_API_URL = $base
    $env:E2E_API_BASE_URL = $base
    $env:E2E_BASE_URL = 'http://localhost:3000'
    Push-Location (Join-Path $PSScriptRoot '../../CatPjt-TranslaCat-fe')
    try {
        & .\node_modules\.bin\playwright.cmd test e2e/integration/writing-cutover.spec.ts --project=integration-chromium --reporter=list
        if ($LASTEXITCODE -ne 0) { throw 'Writing browser verification failed' }
    } finally { Pop-Location }
} else {
    # 실행: 생성 완료 뒤 현재 공개 ID 형식과 답변·중복 제출을 검사한다.
    $types = if ($AllModes) { @('FREE', 'GUIDED', 'TRANSLATION') } else { @('FREE') }
    foreach ($type in $types) {
        $path = "/language-learning/writing/daily?writingType=$type"
        $set = Invoke-TestApi GET $path $null $headers
        $deadline = (Get-Date).AddSeconds(40)
        while ($set.status -eq 'GENERATING' -and (Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 150
            $set = Invoke-TestApi GET $path $null $headers
        }
        if ($set.status -ne 'READY' -or $set.items.Count -ne $sentenceCount -or $set.dailySetId -ge 0) {
            throw "Writing type=$type generation state=$($set.status)"
        }
        $invalidId = -$set.dailySetId
        $null = Invoke-TestApi POST "/language-learning/writing/daily/$invalidId/regenerate" $null $headers 400 'LEARNING_ID_INVALID'
        $answerPath = "/language-learning/writing/daily/items/$($set.items[0].itemId)/answers"
        foreach ($invalid in @(@{}, @{answer=$null}, @{answer=' '})) {
            $null = Invoke-TestApi POST $answerPath $invalid $headers 400 'LANGUAGE_LEARNING_ANSWER_NOT_ALLOWED'
        }
        foreach ($item in $set.items) {
            $answer = @{answer='Synthetic answer'; contentRevision=$item.contentRevision}
            $null = Invoke-TestApi POST "/language-learning/writing/daily/items/$($item.itemId)/answers" $answer $headers
        }
        $deadline = (Get-Date).AddSeconds(30)
        do {
            Start-Sleep -Milliseconds 150
            $set = Invoke-TestApi GET $path $null $headers
        } while ($set.status -ne 'COMPLETED' -and (Get-Date) -lt $deadline)
        if ($set.status -ne 'COMPLETED') { throw 'Writing evaluation did not complete' }
        $null = Invoke-TestApi POST "/language-learning/writing/daily/items/$($item.itemId)/answers" $answer $headers 400 'LANGUAGE_LEARNING_ANSWER_NOT_ALLOWED'
        Write-Output "PASS: type=$type count=$sentenceCount actual BE/LL/Python/DB, negative ID, legacy rejection, evaluation, duplicate"
    }
}
$afterCalls = if (Test-Path -LiteralPath $statsFile) { (Get-Content -Raw $statsFile | ConvertFrom-Json).modelCalls } else { 0 }
Write-Output ("Synthetic model calls=" + ($afterCalls-$beforeCalls) + " elapsedSeconds=" + [math]::Round(((Get-Date)-$started).TotalSeconds,2) + ' paidCalls=0')
