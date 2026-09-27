param([switch]$Smoke, [switch]$Failures, [switch]$ReadAloud, [switch]$Browser, [switch]$CrashRecovery, [switch]$KeywordScheduling,
    [ValidateSet('ALL','WRITING','PRACTICE','LISTENING','SPEAKING')][string]$KeywordFeature='ALL')
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18767/api/v1'
$container = '9d6e91ffcea2'
$catalog = 'translacat_ll_it_live_7cb72f72'
if ($catalog -notmatch '^translacat_ll_it_live_[0-9a-f]{8}$') { throw 'Scratch catalog required' }
$fixtureRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../.codex-workspace/verification/ai/runtime'))
$control = Join-Path $fixtureRoot 'speaking-control.json'
$stats = Join-Path $fixtureRoot 'synthetic_execution_stats.json'
$speechStats = Join-Path $fixtureRoot 'speaking-speech-stats.json'
if (Test-Path -LiteralPath $control) { throw 'Another Speaking fixture control is active' }
$started = Get-Date
$beforeStats = if (Test-Path -LiteralPath $stats) { Get-Content -Raw -LiteralPath $stats | ConvertFrom-Json } else { @{modelCalls=0;processId=$null} }
$before = $beforeStats.modelCalls
$speechBefore = if (Test-Path -LiteralPath $speechStats) { Get-Content -Raw -LiteralPath $speechStats | ConvertFrom-Json -AsHashtable } else { @{} }

function Invoke-SpeakingApi($method, $path, $payload, $headers = @{}, $expected = 200, $code = $null) {
    $arguments = @{Uri="$base$path"; Method=$method; Headers=$headers; SkipHttpErrorCheck=$true; TimeoutSec=35}
    if ($null -ne $payload) { $arguments.ContentType='application/json'; $arguments.Body=$payload | ConvertTo-Json -Depth 30 -Compress }
    $response = Invoke-WebRequest @arguments
    if ([int]$response.StatusCode -ne $expected) { throw "Synthetic API status mismatch: $method $path actual=$($response.StatusCode) expected=$expected" }
    if ($response.Content) {
        $body = ($response.Content | ConvertFrom-Json).body
        if ($code -and $body.errorCode -ne $code) { throw "Synthetic error mismatch: expected=$code actual=$($body.errorCode)" }
        return $body
    }
}

function New-SpeakingLearner($initialSentenceCount=$null) {
    # 합성 계정의 자격증명은 메모리에만 두고 실제 인증·설정 경로를 실행한다.
    $identity = [guid]::NewGuid().ToString('N')
    $email = "speaking-$identity@example.test"
    $password = 'Synthetic-' + [guid]::NewGuid().ToString('N')
    $registered = Invoke-SpeakingApi POST '/auth/register' @{email=$email; password=$password; username='Synthetic Speaking'}
    $login = Invoke-SpeakingApi POST '/auth/login' @{email=$email; password=$password}
    $headers = @{Authorization="Bearer $($login.accessToken)"}
    $settings = @{originLanguage='ko'; learningLanguage='en'; timezone='Asia/Seoul'}
    if ($null -ne $initialSentenceCount) { $settings.dailySentenceCount=$initialSentenceCount }
    $null = Invoke-SpeakingApi PATCH '/language-learning/settings' $settings $headers
    return @{Id=[long]$registered.id; Headers=$headers; Login=$login}
}

function Set-SpeechControl($mode, $scenario='normal', $stage=$null) {
    [IO.File]::WriteAllText($control, (@{speechEnabled=$true; practiceMode=$mode; scenario=$scenario; stage=$stage} | ConvertTo-Json -Compress))
}

function New-SpeakingSession($learner, $mode='FREE', $start='AI_FIRST') {
    $request = @{customTopic="TRANSLACAT_SYNTHETIC_SPEAKING_$([guid]::NewGuid().ToString('N'))"; practiceMode=$mode;
        conversationStartMode=$start; correctionMode='CONVERSATION'; targetMinutes=5; voiceId='marin'; playbackSpeed='NORMAL'; idempotencyKey=[guid]::NewGuid().ToString('N')}
    $session = Invoke-SpeakingApi POST '/language-learning/speaking/sessions' $request $learner.Headers
    if ($session.id -ge 0 -or $session.status -ne 'IN_PROGRESS') { throw 'Speaking session not owned by LL' }
    $same = Invoke-SpeakingApi POST '/language-learning/speaking/sessions' $request $learner.Headers
    if ($same.id -ne $session.id) { throw 'Session creation duplicated' }
    return $session
}

function New-SyntheticWav([int]$seconds=20) {
    $stream = [IO.MemoryStream]::new()
    $writer = [IO.BinaryWriter]::new($stream)
    $frames = 16000*$seconds
    $writer.Write([Text.Encoding]::ASCII.GetBytes('RIFF')); $writer.Write([int](36+2*$frames))
    $writer.Write([Text.Encoding]::ASCII.GetBytes('WAVEfmt ')); $writer.Write([int]16)
    $writer.Write([short]1); $writer.Write([short]1); $writer.Write([int]16000); $writer.Write([int]32000)
    $writer.Write([short]2); $writer.Write([short]16); $writer.Write([Text.Encoding]::ASCII.GetBytes('data')); $writer.Write([int](2*$frames))
    for ($sample=0; $sample -lt $frames; $sample++) { $writer.Write([short](3000*[math]::Sin($sample*.05))) }
    $writer.Flush()
    $bytes = $stream.ToArray()
    $writer.Dispose()
    return ,$bytes
}

function Send-SpeakingAudio($learner, $sessionId, $grant, [byte[]]$bytes, $rerecord=$false, $expected=200) {
    # 외부 multipart Controller와 Gateway를 그대로 통과시킨다. 업무 HTTP를 테스트 함수로 대체하지 않는다.
    $client = [Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds(35)
    $client.DefaultRequestHeaders.Add('Authorization', $learner.Headers.Authorization)
    $form = [Net.Http.MultipartFormDataContent]::new()
    $context = @{turnId=$grant.turnId; uploadToken=$grant.uploadToken; durationSeconds=20.0; assistanceUsage=@(); rerecord=$rerecord} | ConvertTo-Json -Compress
    $part = [Net.Http.StringContent]::new($context, [Text.Encoding]::UTF8, 'application/json')
    $form.Add($part, 'context')
    $audio = [Net.Http.ByteArrayContent]::new($bytes)
    $audio.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::Parse('audio/wav')
    $form.Add($audio, 'audio', 'synthetic.wav')
    try {
        $response = $client.PostAsync("$base/language-learning/speaking/sessions/$sessionId/turns", $form).GetAwaiter().GetResult()
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if ([int]$response.StatusCode -ne $expected) { throw "Synthetic multipart status mismatch: actual=$([int]$response.StatusCode) expected=$expected" }
        return ($content | ConvertFrom-Json).body
    } finally { $form.Dispose(); $client.Dispose() }
}

function Add-SpeakingTurn($learner, $sessionId, [int]$index, [byte[]]$bytes, $problem=$null, $attempt=$null) {
    $grant = Invoke-SpeakingApi POST "/language-learning/speaking/sessions/$sessionId/turns/upload-url" @{turnIndex=$index;idempotencyKey=[guid]::NewGuid().ToString('N');problemIndex=$problem;attemptIndex=$attempt} $learner.Headers
    $turn = Send-SpeakingAudio $learner $sessionId $grant $bytes
    if ($turn.status -ne 'READY') { throw "Synthetic turn failed: stage=$($turn.failedStage) code=$($turn.errorCode) status=$($turn.status)" }
    return $turn
}

function Wait-SpeakingResult($learner, $sessionId) {
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $detail = Invoke-SpeakingApi GET "/language-learning/speaking/sessions/$sessionId" $null $learner.Headers
        if ($detail.session.resultStatus -notin @('PENDING','RUNNING','EVALUATING')) { return $detail }
        Start-Sleep -Milliseconds 120
    } while ((Get-Date) -lt $deadline)
    throw 'Speaking result verification deadline exceeded'
}

function Assert-SpeakingPersistence($learner, $sessionId, $formal) {
    # DB 검증은 확인된 테스트 catalog와 이번 합성 사용자·세션으로 제한한다.
    $entry = docker inspect --format '{{json .Config.Env}}' $container | ConvertFrom-Json | Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' } | Select-Object -First 1
    $password = $entry.Substring('MYSQL_ROOT_PASSWORD='.Length)
    $id = -[long]$sessionId
    $userId = [long]$learner.Id
    $sql = "SELECT CONCAT((SELECT COUNT(*) FROM language_learning_speaking_session WHERE id=$id AND user_id=$userId),':',(SELECT COUNT(*) FROM language_learning_speaking_evaluation_job WHERE session_id=$id AND status='SUCCEEDED'),':',(SELECT COUNT(*) FROM language_learning_activity WHERE user_id=$userId AND source='SPEAKING' AND reference_id='ll-speaking-$id'),':',(SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='translacat_be_it_live_7cb72f72' AND TABLE_NAME='language_learning_speaking_session'));"
    $saved = docker exec -e "MYSQL_PWD=$password" $container mysql -uroot $catalog --batch --skip-column-names -e $sql
    if ($LASTEXITCODE -ne 0 -or $saved.Trim() -notmatch '^1:[1-6]:1:0$') { throw 'Speaking persistence ownership mismatch' }
    if (-not $formal) {
        $score = docker exec -e "MYSQL_PWD=$password" $container mysql -uroot $catalog --batch --skip-column-names -e "SELECT COUNT(*) FROM language_learning_activity WHERE user_id=$userId AND source='SPEAKING' AND overall_score IS NOT NULL;"
        if ($LASTEXITCODE -ne 0 -or $score.Trim() -ne '0') { throw 'Free coaching created official score' }
    }
}

try {
    $audio = New-SyntheticWav
    if ($KeywordScheduling) {
        # 준비: 각 문맥은 독립 합성 사용자를 사용해 먼저 만든 Writing 세트가 다른 검사의 오류를 숨기지 않게 한다.
        foreach ($name in @('writing','practice','listening')) {
            if (Test-Path -LiteralPath (Join-Path $fixtureRoot "$name-control.json")) { throw 'Another feature fixture control is active' }
        }
        $entry = docker inspect --format '{{json .Config.Env}}' $container | ConvertFrom-Json | Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' } | Select-Object -First 1
        $dbSecret = $entry.Substring('MYSQL_ROOT_PASSWORD='.Length)
        $features = if ($KeywordFeature -eq 'ALL') { @('WRITING','PRACTICE','LISTENING','SPEAKING') } else { @($KeywordFeature) }
        foreach ($feature in $features) {
            $learner = New-SpeakingLearner 1
            [long]$userId = $learner.Id
            $sql = @"
INSERT INTO language_learning_level_test_session (session_uid,user_id,session_type,status,origin_language,learning_language,timezone,current_question_number,current_complexity_band,base_level_score,proficiency_band,domain_scores_json,started_at,last_activity_at,completed_at,completed_date,idempotency_key)
VALUES(UUID(),$userId,'INITIAL','COMPLETED','ko','en','Asia/Seoul',20,3,60,'INTERMEDIATE','{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_DATE(),'synthetic-keyword-context-prerequisite');
SET @session=LAST_INSERT_ID();
INSERT INTO language_learning_level_test_baseline(user_id,session_id,completion_id,session_type,base_level_score,proficiency_band,completed_date,started_at,completed_at)
VALUES($userId,@session,UUID(),'INITIAL',60,'INTERMEDIATE',UTC_DATE(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO language_learning_profile(user_id,profile_version,state,base_level_score,evaluation_count,confidence,trend,additional_signals_json,created_at,updated_at,created_by,updated_by)
VALUES($userId,'PROFILE','ACTIVE',60,0,0.0,'stable','{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'TEST','TEST');
"@
            docker exec -e "MYSQL_PWD=$dbSecret" $container mysql -uroot $catalog -e $sql
            if ($LASTEXITCODE -ne 0) { throw 'Synthetic keyword prerequisite failed' }
            $activeTopic = 'TRANSLACAT_SYNTHETIC_SPEAKING_ACTIVE'
            if ($feature -eq 'SPEAKING') {
                $activeKeyword = Invoke-SpeakingApi POST '/language-learning/keywords/custom' @{text=$activeTopic;type='TOPIC'} $learner.Headers
                if (-not $activeKeyword.active -or $null -ne $activeKeyword.pendingEffectiveDate) { throw 'Initial keyword was not immediately active' }
            }
            $session = New-SpeakingSession $learner 'FREE' 'USER_FIRST'
            $keyword = Invoke-SpeakingApi POST '/language-learning/keywords/custom' @{text="Synthetic next $([guid]::NewGuid().ToString('N'))";type='TOPIC'} $learner.Headers
            [long]$keywordId = $keyword.id
            $tomorrow = [datetime]::Parse($session.learningDate).AddDays(1).ToString('yyyy-MM-dd')
            if ([datetime]::Parse($keyword.pendingEffectiveDate).ToString('yyyy-MM-dd') -ne $tomorrow) { throw 'Keyword was not reserved for tomorrow' }

            # 실행: 실제 BE→LL 문맥과 범용 Python 모델 경로를 사용한다. Speaking 후보 조회 뒤에는 기존 활성 세션 보호 400이 정상이다.
            $limit = (Get-Date).AddSeconds(30)
            switch ($feature) {
                'WRITING' {
                    do {
                        $created = Invoke-SpeakingApi GET '/language-learning/writing/daily?writingType=FREE' $null $learner.Headers
                        if ($created.status -ne 'GENERATING') { break }
                        Start-Sleep -Milliseconds 150
                    } while ((Get-Date) -lt $limit)
                    if ($created.items.Count -ne 1) { throw 'Writing context generation did not complete' }
                }
                'PRACTICE' {
                    $created = Invoke-SpeakingApi GET '/language-learning/practice/today?domain=READING&mode=COMPREHENSION' $null $learner.Headers
                    do {
                        $ready = Invoke-SpeakingApi GET "/language-learning/practice/sets/$($created.practiceSetId)" $null $learner.Headers
                        if ($ready.generationStatus -notin @('PENDING','GENERATING')) { break }
                        Start-Sleep -Milliseconds 150
                    } while ((Get-Date) -lt $limit)
                    if ($ready.generationStatus -ne 'READY') { throw 'Practice context generation did not complete' }
                }
                'LISTENING' {
                    $created = Invoke-SpeakingApi POST '/language-learning/listening/daily-sets' @{learningMode='DICTATION';difficulty='MY_LEVEL';itemCount=1} $learner.Headers
                    do {
                        $ready = Invoke-SpeakingApi GET "/language-learning/listening/daily-sets/$($created.dailySetId)" $null $learner.Headers
                        if ($ready.readyItemCount -eq 1 -and -not $ready.generationInProgress) { break }
                        Start-Sleep -Milliseconds 150
                    } while ((Get-Date) -lt $limit)
                    if ($ready.readyItemCount -ne 1) { throw 'Listening context generation did not complete' }
                }
                'SPEAKING' {
                    $null = Invoke-SpeakingApi POST '/language-learning/speaking/sessions' @{keywordBasedTopic=$true;practiceMode='FREE';conversationStartMode='AI_FIRST';correctionMode='CONVERSATION';targetMinutes=5;idempotencyKey=[guid]::NewGuid().ToString('N')} $learner.Headers 400 'SESSION_NOT_ACTIVE'
                }
            }

            # 검증: 읽기 전용 SQL로 활성 상태·예약 날짜·Speaking 공식 Activity 부재를 교차 확인한다.
            $audit = docker exec -e "MYSQL_PWD=$dbSecret" $container mysql -uroot $catalog --batch --skip-column-names -e "SELECT CONCAT(active,'|',pending_effective_date) FROM language_learning_custom_keyword WHERE user_id=$userId AND id=$keywordId; SELECT COUNT(*) FROM language_learning_activity WHERE user_id=$userId AND source='SPEAKING';"
            if ($LASTEXITCODE -ne 0 -or @($audit).Count -ne 2 -or $audit[0] -ne "0|$tomorrow" -or $audit[1] -ne '0') { throw "Keyword scheduling changed during $feature context" }
            Write-Output "PASS: $feature context preserves tomorrow keyword after public Speaking without official evaluation"

            if ($feature -eq 'SPEAKING') {
                # 실행: FREE 원본은 평가 생략을 허용하지 않으므로 합성 발화 후 정상 코칭 완료로 활성 세션을 닫는다.
                Set-SpeechControl 'FREE'
                $null = Add-SpeakingTurn $learner $session.id 1 $audio
                $null = Invoke-SpeakingApi POST "/language-learning/speaking/sessions/$($session.id)/complete" @{skipEvaluation=$false} $learner.Headers
                $completed = Wait-SpeakingResult $learner $session.id
                if ($completed.session.status -ne 'COMPLETED' -or $completed.session.resultStatus -ne 'SUCCEEDED') { throw 'First Speaking session did not complete normally' }
                $created = Invoke-SpeakingApi POST '/language-learning/speaking/sessions' @{keywordBasedTopic=$true;practiceMode='FREE';conversationStartMode='AI_FIRST';correctionMode='CONVERSATION';targetMinutes=5;idempotencyKey=[guid]::NewGuid().ToString('N')} $learner.Headers

                # 검증: 새 문맥이 실제 후보를 선택하고 opening까지 성공한다. 완료 Activity와 공식 평가 결과는 구분한다.
                if ($created.id -eq $session.id -or $created.status -ne 'IN_PROGRESS' -or $created.topicTitle -ne $activeTopic -or $created.resolvedStartMode -ne 'AI_FIRST' -or [string]::IsNullOrWhiteSpace($created.openingAssistantText)) { throw 'Second Speaking keyword context did not open successfully' }
                $audit = docker exec -e "MYSQL_PWD=$dbSecret" $container mysql -uroot $catalog --batch --skip-column-names -e "SELECT CONCAT(active,'|',pending_effective_date) FROM language_learning_custom_keyword WHERE user_id=$userId AND id=$keywordId; SELECT COUNT(*) FROM language_learning_activity WHERE user_id=$userId AND source='SPEAKING'; SELECT COUNT(*) FROM language_learning_speaking_result r JOIN language_learning_speaking_session s ON s.id=r.session_id WHERE s.user_id=$userId AND r.result_kind='SCORED_EVALUATION';"
                if ($LASTEXITCODE -ne 0 -or @($audit).Count -ne 3 -or $audit[0] -ne "0|$tomorrow" -or $audit[1] -ne '1' -or $audit[2] -ne '0') { throw 'Successful Speaking keyword context changed tomorrow reservation or official evaluation' }
                Write-Output 'PASS: completed FREE coaching then new AI_FIRST keyword session READY, current topic selected, tomorrow keyword preserved, completion Activity1 and official evaluation0'
            }
        }
    }
    if (-not $ReadAloud -and -not $Failures -and -not $Browser -and -not $CrashRecovery -and -not $KeywordScheduling) {
    $learner = New-SpeakingLearner
    $other = New-SpeakingLearner
    Set-SpeechControl 'FREE'
    $session = New-SpeakingSession $learner
    $id = $session.id
    $keyword = Invoke-SpeakingApi POST '/language-learning/keywords/custom' @{text="Synthetic speaking keyword $([guid]::NewGuid().ToString('N'))";type='VOCABULARY'} $learner.Headers
    $expectedDate = [datetime]::Parse($session.learningDate).AddDays(1).ToString('yyyy-MM-dd')
    if ([datetime]::Parse($keyword.pendingEffectiveDate).ToString('yyyy-MM-dd') -ne $expectedDate) { throw 'LL Speaking start did not reserve keyword for next learning date' }
    $null = Invoke-SpeakingApi GET "/language-learning/speaking/sessions/$id" $null $other.Headers 400 'SESSION_NOT_FOUND'
    $null = Invoke-SpeakingApi GET "/language-learning/speaking/sessions/$(-$id)" $null $learner.Headers 400 'LEARNING_ID_INVALID'
    $first = Add-SpeakingTurn $learner $id 1 $audio
    $null = Add-SpeakingTurn $learner $id 2 $audio
    $report = Invoke-SpeakingApi POST "/language-learning/speaking/sessions/$id/turns/$($first.id)/stt-reports" @{reportType='WRONG_TEXT';expectedText='Synthetic expected text';audioAnalysisConsent=$true;supportRequested=$false} $learner.Headers
    if ($null -eq $report.audioRetentionUntil -or $report.id -ge 0) { throw 'STT report retention binding failed' }
    $supported = Invoke-SpeakingApi POST "/language-learning/speaking/stt-reports/$($report.id)/support" @{} $learner.Headers
    if (-not $supported.supportRequested) { throw 'STT support status missing' }
    $null = Invoke-SpeakingApi GET "/language-learning/speaking/stt-reports/$($report.id)" $null $other.Headers 400 'STT_REPORT_NOT_FOUND'
    foreach ($audioPath in @($session.openingAssistantAudioUrl, $first.userAudioUrl, $first.assistantAudioUrl)) {
        $response = Invoke-WebRequest "http://127.0.0.1:18767$audioPath" -Headers $learner.Headers -TimeoutSec 5
        if ($response.StatusCode -ne 200 -or $response.Headers['Cache-Control'] -notmatch 'no-store') { throw 'Speaking audio HTTP contract changed' }
    }
    $null = Invoke-SpeakingApi POST "/language-learning/speaking/sessions/$id/assistance" @{type='HINT';targetTurnId=$first.id} $learner.Headers
    $null = Invoke-SpeakingApi POST "/language-learning/speaking/sessions/$id/complete" @{skipEvaluation=$false} $learner.Headers
    $finished = Wait-SpeakingResult $learner $id
    if ($finished.session.resultStatus -ne 'SUCCEEDED' -or $null -eq $finished.coachingResult) { throw 'Free coaching did not finish' }
    Assert-SpeakingPersistence $learner $id $false
    Write-Output 'PASS: real multipart/audio, Free coaching, duplicate, foreign-user/retired ID and LL DB ownership'

    if (-not $Smoke) {
        # 실행: Guided의 원본 점수·Growth와 별도 Read Aloud 문제/세션 결과를 실제 업무 경로로 검증한다.
        Set-SpeechControl 'GUIDED'
        $guidedLearner = New-SpeakingLearner
        $guided = New-SpeakingSession $guidedLearner 'GUIDED'
        1..5 | ForEach-Object { $null = Add-SpeakingTurn $guidedLearner $guided.id $_ $audio }
        $null = Invoke-SpeakingApi POST "/language-learning/speaking/sessions/$($guided.id)/complete" @{skipEvaluation=$false} $guidedLearner.Headers
        $result = Wait-SpeakingResult $guidedLearner $guided.id
        if ($result.session.evaluationStatus -ne 'EVALUATED') { throw "Guided result failed: $($result.session.evaluationStatus)" }
        $evaluation = Invoke-SpeakingApi GET "/language-learning/speaking/sessions/$($guided.id)/evaluation" $null $guidedLearner.Headers
        if ($evaluation.metrics.Count -ne 8 -or $evaluation.overallScore -le 0) { throw 'Guided original evaluation missing' }
        Assert-SpeakingPersistence $guidedLearner $guided.id $true
        Write-Output 'PASS: Guided evaluation and official Growth'
    }
    }

    if ($Browser) {
        Set-SpeechControl 'FREE'
        $browserLearner = New-SpeakingLearner
        $userId = [long]$browserLearner.Id
        $entry = docker inspect --format '{{json .Config.Env}}' $container | ConvertFrom-Json | Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' } | Select-Object -First 1
        $password = $entry.Substring('MYSQL_ROOT_PASSWORD='.Length)
        # UI 진입의 원래 완료 LevelTest 전제만 합성 계정에 준비한다. Speaking 업무 자료는 화면이 생성한다.
        $sql = @"
INSERT INTO language_learning_level_test_session (session_uid,user_id,session_type,status,origin_language,learning_language,timezone,current_question_number,current_complexity_band,base_level_score,proficiency_band,domain_scores_json,started_at,last_activity_at,completed_at,completed_date,idempotency_key)
VALUES(UUID(),$userId,'INITIAL','COMPLETED','ko','en','Asia/Seoul',20,3,60,'INTERMEDIATE','{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_DATE(),'synthetic-speaking-ui-prerequisite');
SET @session=LAST_INSERT_ID();
INSERT INTO language_learning_level_test_baseline(user_id,session_id,completion_id,session_type,base_level_score,proficiency_band,completed_date,started_at,completed_at)
VALUES($userId,@session,UUID(),'INITIAL',60,'INTERMEDIATE',UTC_DATE(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO language_learning_profile(user_id,profile_version,state,base_level_score,evaluation_count,confidence,trend,additional_signals_json,created_at,updated_at,created_by,updated_by)
VALUES($userId,'PROFILE','ACTIVE',60,0,0.0,'stable','{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'TEST','TEST') ON DUPLICATE KEY UPDATE state='ACTIVE',base_level_score=60;
"@
        docker exec -e "MYSQL_PWD=$password" $container mysql -uroot $catalog -e $sql
        if ($LASTEXITCODE -ne 0) { throw 'Speaking synthetic UI prerequisite failed' }

        # 준비: 브라우저 입력 오디오를 중앙 검증 디렉터리에 저장한다.
        $audioPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../.codex-workspace/verification/ll/runtime/speaking-ui-input.wav'))
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($audioPath)) -Force | Out-Null
        [IO.File]::WriteAllBytes($audioPath, $audio)
        $env:E2E_SPEAKING_AUDIO_PATH = $audioPath
        $env:E2E_SPEAKING_CONTROL_PATH = $control
        $env:E2E_SPEAKING_ACCESS_TOKEN = $browserLearner.Login.accessToken
        $env:E2E_SPEAKING_REFRESH_TOKEN = $browserLearner.Login.refreshToken
        $env:E2E_SPEAKING_PUBLIC_ID = $browserLearner.Login.publicId
        $env:NEXTAUTH_SECRET = 'synthetic-cutover-nextauth-secret-20260926'
        $env:NEXTAUTH_URL = 'http://localhost:3000'
        $env:NEXT_PUBLIC_API_URL = $base
        $env:E2E_API_BASE_URL = $base
        $env:E2E_BASE_URL = 'http://localhost:3000'
        Push-Location (Join-Path $PSScriptRoot '../../CatPjt-TranslaCat-fe')
        try {
            & .\node_modules\.bin\playwright.cmd test e2e/integration/speaking-cutover.spec.ts --project=integration-chromium --reporter=list
            if ($LASTEXITCODE -ne 0) { throw 'Speaking actual UI verification failed' }
        } finally { Pop-Location }
    }

    if ($CrashRecovery) {
        $marker = Join-Path $fixtureRoot 'speaking-provider-held.json'
        if (Test-Path -LiteralPath $marker) { throw 'Another Speaking held Provider marker exists' }
        Set-SpeechControl 'FREE'
        $restartLearner = New-SpeakingLearner
        $restartSession = New-SpeakingSession $restartLearner
        $restartId = $restartSession.id
        $turn = Add-SpeakingTurn $restartLearner $restartId 1 $audio
        Set-SpeechControl 'FREE' 'hold' 'COACHING'
        $null = Invoke-SpeakingApi POST "/language-learning/speaking/sessions/$restartId/complete" @{skipEvaluation=$false} $restartLearner.Headers
        $deadline = (Get-Date).AddSeconds(5)
        do {
            if (Test-Path -LiteralPath $marker) { break }
            Start-Sleep -Milliseconds 50
        } while ((Get-Date) -lt $deadline)
        if (-not (Test-Path -LiteralPath $marker)) { throw 'Speaking in-flight Provider was not reached' }
        try {
            $entry = docker inspect --format '{{json .Config.Env}}' $container | ConvertFrom-Json | Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' } | Select-Object -First 1
            $secret = $entry.Substring('MYSQL_ROOT_PASSWORD='.Length)
            $localId = -[long]$restartId
            $userId = [long]$restartLearner.Id
            $sql = "UPDATE language_learning_speaking_evaluation_job j JOIN language_learning_speaking_session s ON s.id=j.session_id SET j.available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE s.id=$localId AND s.user_id=$userId AND j.problem_index=0 AND j.status='RUNNING'; SELECT ROW_COUNT();"

            # 실제 OS 프로세스를 강제 종료한 뒤 이 합성 job의 lease clock만 전진한다. 실제 900초 대기는 아니다.
            & (Join-Path $PSScriptRoot 'restart-local-ktor.ps1') -BeforeStart {
                $changed = docker exec -e "MYSQL_PWD=$secret" $container mysql -uroot $catalog --batch --skip-column-names -e $sql
                if ($LASTEXITCODE -ne 0 -or $changed.Trim() -ne '1') { throw 'Speaking synthetic lease did not match one held job' }
                Set-SpeechControl 'FREE'
            }
            $recovered = Wait-SpeakingResult $restartLearner $restartId
            if ($recovered.session.resultStatus -ne 'SUCCEEDED' -or $recovered.turns[0].transcript -ne $turn.transcript) { throw 'Speaking OS recovery lost turn or coaching job' }
            Assert-SpeakingPersistence $restartLearner $restartId $false
            Write-Output 'PASS: actual owned Ktor OS force-stop/restart recovered coaching intent with prior turn preserved; one synthetic lease clock advanced'
        } finally {
            if (Test-Path -LiteralPath $marker) { Remove-Item -LiteralPath $marker }
        }
    }

    if ($ReadAloud) {
        Set-SpeechControl 'READ_ALOUD'
        $reader = New-SpeakingLearner
        $reading = New-SpeakingSession $reader 'READ_ALOUD'
        $readingId = $reading.id
        foreach ($problem in 1..5) {
            $firstAttempt = Add-SpeakingTurn $reader $readingId (2*$problem-1) $audio $problem 1
            if ($Failures -and $problem -eq 1) {
                # 실행: 실패한 재녹음은 이미 저장한 전사와 오디오를 보존한다.
                $grant = Invoke-SpeakingApi POST "/language-learning/speaking/sessions/$readingId/turns/$($firstAttempt.id)/rerecord/upload-url" @{} $reader.Headers
                Set-SpeechControl 'READ_ALOUD' 'stt_failure' 'STT'
                $null = Send-SpeakingAudio $reader $readingId $grant $audio $true 400
                Set-SpeechControl 'READ_ALOUD'
                $protected = Invoke-SpeakingApi GET "/language-learning/speaking/sessions/$readingId/turns/$($firstAttempt.id)" $null $reader.Headers
                if ($protected.transcript -ne $firstAttempt.transcript -or $protected.userAudioUrl -ne $firstAttempt.userAudioUrl -or $protected.status -ne 'READY') { throw 'Failed rerecord changed existing evidence' }
            }
            $null = Add-SpeakingTurn $reader $readingId (2*$problem) $audio $problem 2
            $null = Invoke-SpeakingApi POST "/language-learning/speaking/sessions/$readingId/read-aloud/problems/$problem/evaluate" @{} $reader.Headers
            $null = Invoke-SpeakingApi POST "/language-learning/speaking/sessions/$readingId/read-aloud/problems/$problem/evaluate" @{} $reader.Headers
            if ($problem -eq 1) {
                $null = Invoke-SpeakingApi POST "/language-learning/speaking/sessions/$readingId/turns/$($firstAttempt.id)/exclude" @{} $reader.Headers 400 'SESSION_NOT_ACTIVE'
                $null = Invoke-SpeakingApi POST "/language-learning/speaking/sessions/$readingId/turns/$($firstAttempt.id)/rerecord/upload-url" @{} $reader.Headers 400 'TURN_PROCESSING'
            }
        }
        $result = Wait-SpeakingResult $reader $readingId
        $deadline = (Get-Date).AddSeconds(30)
        do {
            $problems = @(Invoke-SpeakingApi GET "/language-learning/speaking/sessions/$readingId/read-aloud/problems" $null $reader.Headers)
            if (@($problems | Where-Object { $_.status -in @('PENDING','EVALUATING') }).Count -eq 0) { break }
            Start-Sleep -Milliseconds 120
        } while ((Get-Date) -lt $deadline)
        if ($result.session.evaluationStatus -ne 'EVALUATED' -or $problems.Count -ne 5 -or @($problems | Where-Object { $_.status -ne 'EVALUATED' }).Count -gt 0) { throw 'Read Aloud problem/session result failed' }
        Assert-SpeakingPersistence $reader $readingId $true
        Write-Output 'PASS: Read Aloud five problems, duplicate submit, immutable submitted evidence and separate session Growth'
    }

    if ($Failures) {
        $failureLearner = New-SpeakingLearner
        $request = @{customTopic="TRANSLACAT_SYNTHETIC_SPEAKING_$([guid]::NewGuid().ToString('N'))"; practiceMode='FREE';
            conversationStartMode='AI_FIRST'; correctionMode='CONVERSATION';targetMinutes=5;voiceId='marin';playbackSpeed='NORMAL';idempotencyKey=[guid]::NewGuid().ToString('N')}
        Set-SpeechControl 'FREE' 'conversation_failure' 'CONVERSATION'
        $null = Invoke-SpeakingApi POST '/language-learning/speaking/sessions' $request $failureLearner.Headers 400 'CONVERSATION_GENERATION_FAILED'
        $status = @(Invoke-SpeakingApi GET '/language-learning/speaking/sessions/today/status' $null $failureLearner.Headers)
        if (@($status | Where-Object { $null -ne $_.sessionId }).Count -ne 0) { throw 'Failed opening leaked into daily status' }
        Set-SpeechControl 'FREE'
        $recovered = Invoke-SpeakingApi POST '/language-learning/speaking/sessions' $request $failureLearner.Headers
        $recoveredId = $recovered.id

        # 실행: 저장된 업로드의 STT 실패를 실제 수동 재시도로 복구하고 평가 실패 intent도 재사용한다.
        Set-SpeechControl 'FREE' 'stt_failure' 'STT'
        $grant = Invoke-SpeakingApi POST "/language-learning/speaking/sessions/$recoveredId/turns/upload-url" @{turnIndex=1;idempotencyKey=[guid]::NewGuid().ToString('N')} $failureLearner.Headers
        $failedTurn = Send-SpeakingAudio $failureLearner $recoveredId $grant $audio
        if ($failedTurn.status -ne 'PARTIAL_FAILURE' -or $failedTurn.failedStage -ne 'STT') { throw 'STT failure state missing' }
        Set-SpeechControl 'FREE'
        $replayed = Invoke-SpeakingApi POST "/language-learning/speaking/sessions/$recoveredId/turns/$($grant.turnId)/retry" @{} $failureLearner.Headers
        if ($replayed.status -ne 'READY' -or $replayed.manualRetryCount -ne 1) { throw 'STT manual retry failed' }
        Set-SpeechControl 'FREE' 'evaluation_failure' 'COACHING'
        $null = Invoke-SpeakingApi POST "/language-learning/speaking/sessions/$recoveredId/complete" @{skipEvaluation=$false} $failureLearner.Headers
        $failed = Wait-SpeakingResult $failureLearner $recoveredId
        if ($failed.session.resultStatus -ne 'FAILED') { throw 'Coaching failure not preserved' }
        Set-SpeechControl 'FREE'
        $null = Invoke-SpeakingApi POST "/language-learning/speaking/sessions/$recoveredId/evaluation/retry" @{} $failureLearner.Headers
        $recovered = Wait-SpeakingResult $failureLearner $recoveredId
        if ($recovered.session.resultStatus -ne 'SUCCEEDED') { throw 'Coaching retry failed' }
        Assert-SpeakingPersistence $failureLearner $recoveredId $false
        Write-Output 'PASS: failed opening hidden and same-key recovery, STT/manual retry, immutable coaching retry'
    }
} finally {
    if (Test-Path -LiteralPath $control) { Remove-Item -LiteralPath $control }
    $afterStats = if (Test-Path -LiteralPath $stats) { Get-Content -Raw -LiteralPath $stats | ConvertFrom-Json } else { $beforeStats }
    $after = $afterStats.modelCalls
    $modelDelta = if ($beforeStats.processId -eq $afterStats.processId -and $after -ge $before) { $after-$before } else { 'UNVERIFIED_COUNTER_RESET' }
    Write-Output "Synthetic model counters: before=$before after=$after beforeProcessId=$($beforeStats.processId) afterProcessId=$($afterStats.processId)"
    $afterSpeech = if (Test-Path -LiteralPath $speechStats) { Get-Content -Raw -LiteralPath $speechStats | ConvertFrom-Json -AsHashtable } else { @{} }
    $counts = @{}
    foreach ($stage in @('STT','TTS','EVIDENCE')) { $counts[$stage] = [int]$afterSpeech[$stage]-[int]$speechBefore[$stage] }
    Write-Output "Speaking synthetic calls: model=$modelDelta, speech=$($counts | ConvertTo-Json -Compress), paid=0, elapsedSeconds=$([math]::Round(((Get-Date)-$started).TotalSeconds,2))"
}
