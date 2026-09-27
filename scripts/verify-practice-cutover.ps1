param([switch]$Browser, [switch]$CrashRecovery, [switch]$Vocabulary)
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18767/api/v1'
$container = '9d6e91ffcea2'
$catalog = 'translacat_ll_it_live_7cb72f72'
if ($catalog -notmatch '^translacat_ll_it_live_[0-9a-f]{8}$') { throw 'Scratch catalog required' }
$testRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../CatPjt-TranslaCat-ai/.tmp_ktor_m0'))
$control = Join-Path $testRoot 'practice-control.json'
$statsFile = Join-Path $testRoot 'practice-stats.json'
if (Test-Path -LiteralPath $control) { throw 'Another Practice fixture control is active' }
$started = Get-Date
$httpCalls = 0

function Invoke-TestApi($method, $path, $payload, $headers = @{}, $expected = 200, $errorCode = $null) {
    $script:httpCalls++
    $arguments = @{Uri="$base$path"; Method=$method; Headers=$headers; SkipHttpErrorCheck=$true; TimeoutSec=15}
    if ($null -ne $payload) {
        $arguments.ContentType = 'application/json'
        $arguments.Body = $payload | ConvertTo-Json -Depth 20 -Compress
    }
    $response = Invoke-WebRequest @arguments
    if ([int]$response.StatusCode -ne $expected) {
        throw "Synthetic API failed: $method $path status=$($response.StatusCode) expected=$expected"
    }
    if ($response.Content) {
        $body = ($response.Content | ConvertFrom-Json).body
        if ($errorCode -and $body.errorCode -ne $errorCode) { throw 'Practice external error code changed' }
        return $body
    }
}

function New-TestLearner {
    # 합성 계정의 비밀번호와 토큰은 메모리에만 유지한다.
    $identity = [guid]::NewGuid().ToString('N')
    $email = "practice-$identity@example.test"
    $password = 'Synthetic-' + [guid]::NewGuid().ToString('N')
    $registered = Invoke-TestApi POST '/auth/register' @{email=$email; password=$password; username='Synthetic Practice'}
    $login = Invoke-TestApi POST '/auth/login' @{email=$email; password=$password}
    $headers = @{Authorization="Bearer $($login.accessToken)"}
    [long]$userId = $registered.id
    if ($userId -le 0) { throw 'Synthetic user missing' }
    $null = Invoke-TestApi PATCH '/language-learning/settings' @{originLanguage='ko'; learningLanguage='en'; timezone='Asia/Seoul'; dailySentenceCount=1} $headers

    # Reading 진입의 기존 레벨 테스트 전제만 테스트 전용 DB에 준비한다.
    $entry = docker inspect --format '{{json .Config.Env}}' $container | ConvertFrom-Json |
        Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' } | Select-Object -First 1
    $dbPassword = $entry.Substring('MYSQL_ROOT_PASSWORD='.Length)
    $sql = @"
INSERT INTO language_learning_level_test_session (session_uid,user_id,session_type,status,origin_language,learning_language,timezone,current_question_number,current_complexity_band,base_level_score,proficiency_band,domain_scores_json,started_at,last_activity_at,completed_at,completed_date,idempotency_key)
VALUES(UUID(),$userId,'INITIAL','COMPLETED','ko','en','Asia/Seoul',20,3,60,'INTERMEDIATE','{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_DATE(),'synthetic-practice-prerequisite');
SET @session=LAST_INSERT_ID();
INSERT INTO language_learning_level_test_baseline(user_id,session_id,completion_id,session_type,base_level_score,proficiency_band,completed_date,started_at,completed_at)
VALUES($userId,@session,UUID(),'INITIAL',60,'INTERMEDIATE',UTC_DATE(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO language_learning_profile(user_id,profile_version,state,base_level_score,evaluation_count,confidence,trend,additional_signals_json,created_at,updated_at,created_by,updated_by)
VALUES($userId,'PROFILE','ACTIVE',60,0,0.0,'stable','{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'TEST','TEST')
ON DUPLICATE KEY UPDATE state='ACTIVE',base_level_score=60;
"@
    docker exec -e "MYSQL_PWD=$dbPassword" $container mysql -uroot $catalog -e $sql
    if ($LASTEXITCODE -ne 0) { throw 'Synthetic prerequisite insert failed' }
    return @{Id=$userId; Headers=$headers; Login=$login}
}

function Wait-Practice($setId, $headers) {
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $set = Invoke-TestApi GET "/language-learning/practice/sets/$setId" $null $headers
        if ($set.generationStatus -notin @('PENDING','GENERATING')) { return $set }
        Start-Sleep -Milliseconds 150
    } while ((Get-Date) -lt $deadline)
    throw 'Synthetic Practice generation exceeded verification deadline'
}

# 준비: 모델 사용량은 원문 없이 단계별 숫자로만 비교한다.
$before = if (Test-Path -LiteralPath $statsFile) { Get-Content -Raw -LiteralPath $statsFile | ConvertFrom-Json -AsHashtable } else { @{} }
$learner = New-TestLearner
if ($CrashRecovery -and ($Browser -or $Vocabulary)) { throw 'Choose one verification track' }
if ($Vocabulary) {
    # 준비: 신규 어휘 출제는 퇴역 상태로 두고, 이 합성 사용자에게만 과거 10문항 세트와 복습 근거를 준비한다.
    $allStatsFile = Join-Path $testRoot 'synthetic_execution_stats.json'
    $allBefore = Get-Content -Raw -LiteralPath $allStatsFile | ConvertFrom-Json
    $other = New-TestLearner
    $headers = $learner.Headers
    [long]$userId = $learner.Id
    [long]$otherId = $other.Id
    $entry = docker inspect --format '{{json .Config.Env}}' $container | ConvertFrom-Json | Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' } | Select-Object -First 1
    $dbPassword = $entry.Substring('MYSQL_ROOT_PASSWORD='.Length)
    $sql = [Collections.Generic.List[string]]::new()
    $sql.Add("INSERT INTO language_learning_practice_set(user_id,learning_date,domain,mode,complexity_band,question_count,request_json,status,generation_status,started_at) VALUES($userId,UTC_DATE()-INTERVAL 1 DAY,'VOCABULARY','CONTEXTUAL_CHOICE',3,10,'{}','ACTIVE','READY',UTC_TIMESTAMP(6)); SET @vocabulary_set=LAST_INSERT_ID();")
    $sql.Add("INSERT INTO language_learning_vocabulary_mastery(user_id,canonical_key,display_expression,score,evaluation_count,selected_count,last_selected_date) VALUES($userId,'review-key','Review expression',32.5,1,2,UTC_DATE()-INTERVAL 2 DAY),($userId,'unanswered-key','New expression',50,0,0,NULL),($otherId,'review-key','Other expression',90,4,0,NULL);")
    foreach ($order in 1..10) {
        $content = @{order=$order;questionType='SINGLE_CHOICE';difficulty='CURRENT';complexityBand=3;prompt="Synthetic vocabulary question $order";
            options=@('A','B','C','D') | ForEach-Object { @{key=$_;text="Option $_"} };correctAnswer=@('A');skillTag='MEANING';
            explanationOrigin='합성 해설';explanationLearning='Synthetic explanation';targetExpression=$(if ($order -eq 2) {'Review expression'} else {"Expression $order"});
            canonicalKey=$(if ($order -eq 2) {'review-key'} else {"key-$order"});reviewTarget=($order -eq 2)} | ConvertTo-Json -Depth 8 -Compress
        $hex = [Convert]::ToHexString([Text.Encoding]::UTF8.GetBytes($content))
        $sql.Add("INSERT INTO language_learning_practice_question(practice_set_id,order_no,content_json) VALUES(@vocabulary_set,$order,CONVERT(0x$hex USING utf8mb4));")
    }
    $sql.Add('SELECT @vocabulary_set;')
    $localSetId = docker exec -e "MYSQL_PWD=$dbPassword" $container mysql -uroot $catalog --batch --skip-column-names -e ($sql -join "`n")
    if ($LASTEXITCODE -ne 0 -or $localSetId -notmatch '^\d+$') { throw 'Synthetic historical Vocabulary fixture insert failed' }
    $setId = -[long]$localSetId

    # 실행: 실제 BE→LL 조회·첫 답변·재도전·과거 복습 표현의 공식 답변을 처리한다.
    $set = Invoke-TestApi GET "/language-learning/practice/sets/$setId" $null $headers
    if ($set.domain -ne 'VOCABULARY' -or $set.questions.Count -ne 10 -or -not $set.questions[1].reviewTarget) { throw 'Historical Vocabulary set was not readable' }
    if (@($set.questions | Where-Object { $_.correctAnswer.Count -gt 0 -or $_.explanationOrigin }).Count -ne 0) { throw 'Unanswered Vocabulary explanations leaked' }
    $originalOther = Invoke-TestApi GET '/language-learning/practice/vocabulary/mastery' $null $other.Headers
    $null = Invoke-TestApi GET '/language-learning/practice/today?domain=VOCABULARY&mode=CONTEXTUAL_CHOICE' $null $headers 400 'DAILY_VOCABULARY_RETIRED'
    $null = Invoke-TestApi GET "/language-learning/practice/sets/$setId" $null $other.Headers 400 'LANGUAGE_LEARNING_DAILY_SET_NOT_FOUND'
    $first = $set.questions[0]
    if ($Browser) {
        # 실제 기존 세트 화면이 답변·재도전·결과 복습을 수행한다. 인증 세션 외 업무 HTTP는 대체하지 않는다.
        $env:E2E_PRACTICE_ACCESS_TOKEN = $learner.Login.accessToken
        $env:E2E_PRACTICE_REFRESH_TOKEN = $learner.Login.refreshToken
        $env:E2E_PRACTICE_PUBLIC_ID = $learner.Login.publicId
        $env:E2E_VOCABULARY_SET_ID = [string]$setId
        $env:NEXTAUTH_SECRET = 'synthetic-cutover-nextauth-secret-20260926'
        $env:NEXTAUTH_URL = 'http://localhost:3000'
        $env:NEXT_PUBLIC_API_URL = $base
        $env:E2E_API_BASE_URL = $base
        $env:E2E_BASE_URL = 'http://localhost:3000'
        Push-Location (Join-Path $PSScriptRoot '../../CatPjt-TranslaCat-fe')
        try {
            & .\node_modules\.bin\playwright.cmd test e2e/integration/vocabulary-cutover.spec.ts --project=integration-chromium --reporter=list
            if ($LASTEXITCODE -ne 0) { throw 'Vocabulary browser verification failed' }
        } finally { Pop-Location }
    } else {
        $wrong = Invoke-TestApi POST "/language-learning/practice/questions/$($first.questionId)/answers" @{answer=@('B')} $headers
        $retry = Invoke-TestApi POST "/language-learning/practice/questions/$($first.questionId)/answers" @{answer=@('A')} $headers
        if (-not $wrong.official -or $wrong.correct -or $retry.official -or $retry.attemptNo -ne 2 -or -not $retry.correct) { throw 'Vocabulary retry replaced the official first answer' }
        $null = Invoke-TestApi POST "/language-learning/practice/questions/$($first.questionId)/answers" @{answer=@('A')} $headers 400 'LANGUAGE_LEARNING_ANSWER_NOT_ALLOWED'
        $null = Invoke-TestApi POST "/language-learning/practice/questions/$($first.questionId)/answers" @{answer=@('A')} $other.Headers 400 'LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND'
        foreach ($question in $set.questions | Select-Object -Skip 1) {
            $answer = Invoke-TestApi POST "/language-learning/practice/questions/$($question.questionId)/answers" @{answer=@('A')} $headers
            if (-not $answer.official -or -not $answer.correct) { throw 'Vocabulary official answer mismatch' }
        }
    }
    $review = $set.questions[1]
    $null = Invoke-TestApi POST "/language-learning/practice/questions/$($review.questionId)/answers" @{answer=@('A')} $headers 400 'LANGUAGE_LEARNING_ANSWER_NOT_ALLOWED'

    # 검증: mastery 요약·첫 시도 점수·이력·사용자 격리를 실제 외부 DTO와 DB에서 함께 확인한다.
    $completed = Invoke-TestApi GET "/language-learning/practice/sets/$setId" $null $headers
    if ($completed.status -ne 'COMPLETED' -or $completed.officialScore -ne 90 -or $completed.answeredCount -ne 10 -or $completed.correctCount -ne 9) { throw 'Vocabulary first-attempt score changed' }
    $mastery = Invoke-TestApi GET '/language-learning/practice/vocabulary/mastery' $null $headers
    if ($mastery.total -ne 11 -or $mastery.averageScore -ne 62.86 -or $mastery.newCount -ne 1 -or $mastery.learningCount -ne 1 -or $mastery.familiarCount -ne 9 -or $mastery.weakest.Count -ne 10) { throw 'Vocabulary mastery summary changed' }
    $reviewMastery = @($mastery.weakest | Where-Object { $_.canonicalKey -eq 'review-key' })
    if ($reviewMastery.Count -ne 1 -or $reviewMastery[0].score -ne 56.13 -or $reviewMastery[0].evaluationCount -ne 2) { throw 'Vocabulary review mastery weight or duplicate protection changed' }
    $currentOther = Invoke-TestApi GET '/language-learning/practice/vocabulary/mastery' $null $other.Headers
    if (($originalOther | ConvertTo-Json -Depth 8 -Compress) -ne ($currentOther | ConvertTo-Json -Depth 8 -Compress)) { throw 'Other user mastery was changed or exposed' }
    $history = Invoke-TestApi GET '/language-learning/history?source=VOCABULARY&period=30d' $null $headers
    $item = @($history | Where-Object { $_.activityId -eq "VOCABULARY:$setId" })
    if ($item.Count -ne 1 -or $item[0].overallScore -ne 90) { throw 'Vocabulary history lost the official score' }
    $auditSql = "SELECT COUNT(*) FROM language_learning_practice_attempt a JOIN language_learning_practice_question q ON q.id=a.question_id WHERE q.practice_set_id=$localSetId; SELECT CONCAT(score,'|',evaluation_count,'|',selected_count) FROM language_learning_vocabulary_mastery WHERE user_id=$userId AND canonical_key='review-key'; SELECT COUNT(*) FROM language_learning_activity WHERE user_id=$userId AND source='VOCABULARY' AND reference_id='ll-practice-$localSetId' AND overall_score=90; SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='translacat_be_it_live_7cb72f72' AND TABLE_NAME='language_learning_vocabulary_mastery';"
    $audit = docker exec -e "MYSQL_PWD=$dbPassword" $container mysql -uroot $catalog --batch --skip-column-names -e $auditSql
    if ($LASTEXITCODE -ne 0 -or ($audit -join ';') -ne '11;56.13|2|2;1;0') { throw 'Vocabulary LL persistence or Core ownership mismatch' }
    $allAfter = Get-Content -Raw -LiteralPath $allStatsFile | ConvertFrom-Json
    if ($allBefore.processId -ne $allAfter.processId -or $allBefore.modelCalls -ne $allAfter.modelCalls) { throw 'Vocabulary historical review unexpectedly called a model or reset counters' }
    Write-Output "PASS: historical Vocabulary 10 questions, first attempt90, retry and review mastery, duplicate/foreign user protection, summary/history, LL storage and Core mastery table absent; HTTP=$httpCalls modelBefore=$($allBefore.modelCalls) modelAfter=$($allAfter.modelCalls) processId=$($allAfter.processId)"
} elseif ($CrashRecovery) {
    $marker = Join-Path $testRoot 'practice-hold.json'
    if (Test-Path -LiteralPath $marker) { throw 'Another Practice provider hold exists' }
    try {
        # 준비: 실제 p2 모델 HTTP를 지연시키고 이미 공개된 p1의 답변을 저장한다.
        [IO.File]::WriteAllText($control, '{"mode":"COMPREHENSION","failure":"HOLD_P2_PASSAGE"}')
        $headers = $learner.Headers
        $created = Invoke-TestApi GET '/language-learning/practice/today?domain=READING&mode=COMPREHENSION' $null $headers
        $deadline = (Get-Date).AddSeconds(25)
        do {
            $held = Invoke-TestApi GET "/language-learning/practice/sets/$($created.practiceSetId)" $null $headers
            if ($held.generationStatus -eq 'GENERATING' -and $held.questions.Count -eq 3 -and (Test-Path -LiteralPath $marker)) { break }
            Start-Sleep -Milliseconds 150
        } while ((Get-Date) -lt $deadline)
        if ($held.generationStatus -ne 'GENERATING' -or $held.questions.Count -ne 3 -or -not (Test-Path -LiteralPath $marker)) { throw 'Synthetic in-flight model call was not reached' }
        $null = Invoke-TestApi POST "/language-learning/practice/questions/$($held.questions[0].questionId)/answers" @{answer=@('B')} $headers
        $beforeRestart = Invoke-TestApi GET "/language-learning/practice/sets/$($created.practiceSetId)" $null $headers
        $priorQuestions = $beforeRestart.questions | ConvertTo-Json -Depth 20 -Compress

        # 실행: 이 검사의 합성 세트 lease만 만료시킨다. 실제 30분 경과를 기다린 검사는 아니다.
        $entry = docker inspect --format '{{json .Config.Env}}' $container | ConvertFrom-Json |
            Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' } | Select-Object -First 1
        $dbPassword = $entry.Substring('MYSQL_ROOT_PASSWORD='.Length)
        $localId = -[long]$created.practiceSetId
        $userId = [long]$learner.Id
        $sql = "UPDATE language_learning_practice_set SET generation_lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=$localId AND user_id=$userId AND generation_status='GENERATING'; SELECT ROW_COUNT();"
        $changed = docker exec -e "MYSQL_PWD=$dbPassword" $container mysql -uroot $catalog --batch --skip-column-names -e $sql
        if ($LASTEXITCODE -ne 0 -or $changed.Trim() -ne '1') { throw 'Synthetic lease expiration did not match one owned job' }
        [IO.File]::WriteAllText($control, '{}')
        & (Join-Path $PSScriptRoot 'restart-local-ktor.ps1')

        # 검증: 새 OS 프로세스의 worker가 HTTP 재시도 요청 없이 미완성 p2를 회수한다.
        $recovered = Wait-Practice $created.practiceSetId $headers
        if ($recovered.generationStatus -ne 'READY' -or $recovered.questions.Count -ne 5 -or $recovered.answeredCount -ne 1) { throw 'Restart recovery did not complete the retained set' }
        $retained = @($recovered.questions | Select-Object -First 3) | ConvertTo-Json -Depth 20 -Compress
        if ($retained -ne $priorQuestions) { throw 'OS restart changed prior questions or answers' }
        Write-Output 'PASS: actual Ktor OS force-stop/restart recovered an in-flight Practice job; p1 questions/answer retained; only synthetic lease timestamp advanced; no retry HTTP request'
    } finally {
        if (Test-Path -LiteralPath $control) { Remove-Item -LiteralPath $control }
        if (Test-Path -LiteralPath $marker) { Remove-Item -LiteralPath $marker }
    }
} elseif ($Browser) {
    $env:E2E_PRACTICE_ACCESS_TOKEN = $learner.Login.accessToken
    $env:E2E_PRACTICE_REFRESH_TOKEN = $learner.Login.refreshToken
    $env:E2E_PRACTICE_PUBLIC_ID = $learner.Login.publicId
    $env:NEXTAUTH_SECRET = 'synthetic-cutover-nextauth-secret-20260926'
    $env:NEXTAUTH_URL = 'http://localhost:3000'
    $env:NEXT_PUBLIC_API_URL = $base
    $env:E2E_API_BASE_URL = $base
    $env:E2E_BASE_URL = 'http://localhost:3000'
    Push-Location (Join-Path $PSScriptRoot '../../CatPjt-TranslaCat-fe')
    try {
        & .\node_modules\.bin\playwright.cmd test e2e/integration/practice-cutover.spec.ts --project=integration-chromium --reporter=list
        if ($LASTEXITCODE -ne 0) { throw 'Practice browser verification failed' }
    } finally { Pop-Location }
} else {
    # 실행: 실제 생성·답변 HTTP 경로와 퇴역/이전 ID/외부 사용자 거부를 검사한다.
    $headers = $learner.Headers
    $created = Invoke-TestApi GET '/language-learning/practice/today?domain=READING&mode=COMPREHENSION' $null $headers
    $set = Wait-Practice $created.practiceSetId $headers
    if ($set.generationStatus -ne 'READY' -or $set.questions.Count -ne 5 -or $set.practiceSetId -ge 0) { throw "Invalid complete set state=$($set.generationStatus) failure=$($set.generationFailureMessage)" }
    if (@($set.questions | Where-Object { $_.correctAnswer.Count -gt 0 -or $_.explanationOrigin }).Count -ne 0) { throw 'Unanswered explanations leaked' }
    $same = Invoke-TestApi GET '/language-learning/practice/today?domain=READING&mode=COMPREHENSION' $null $headers
    if ($same.practiceSetId -ne $set.practiceSetId) { throw 'Daily creation was not idempotent' }
    $null = Invoke-TestApi GET "/language-learning/practice/sets/$(-$set.practiceSetId)" $null $headers 400 'LEARNING_ID_INVALID'
    $null = Invoke-TestApi GET '/language-learning/practice/today?domain=VOCABULARY&mode=CONTEXTUAL_CHOICE' $null $headers 400 'DAILY_VOCABULARY_RETIRED'
    $other = New-TestLearner
    $null = Invoke-TestApi GET "/language-learning/practice/sets/$($set.practiceSetId)" $null $other.Headers 400 'LANGUAGE_LEARNING_DAILY_SET_NOT_FOUND'
    $first = $set.questions[0]
    $null = Invoke-TestApi POST "/language-learning/practice/questions/$($first.questionId)/answers" @{} $headers 400 'LANGUAGE_LEARNING_ANSWER_NOT_ALLOWED'
    $null = Invoke-TestApi POST "/language-learning/practice/questions/$($first.questionId)/answers" @{answer=$null} $headers 400 'LANGUAGE_LEARNING_ANSWER_NOT_ALLOWED'
    $null = Invoke-TestApi POST "/language-learning/practice/questions/$($first.questionId)/answers" @{answer=@($null)} $headers 400 'LANGUAGE_LEARNING_ANSWER_NOT_ALLOWED'
    $null = Invoke-TestApi POST "/language-learning/practice/questions/$($first.questionId)/answers" @{answer=@('B')} $headers
    $retry = Invoke-TestApi POST "/language-learning/practice/questions/$($first.questionId)/answers" @{answer=@('A')} $headers
    if ($retry.official -or $retry.attemptNo -ne 2) { throw 'Retry replaced official attempt' }
    $null = Invoke-TestApi POST "/language-learning/practice/questions/$($first.questionId)/answers" @{answer=@('A')} $headers 400 'LANGUAGE_LEARNING_ANSWER_NOT_ALLOWED'
    foreach ($question in $set.questions | Select-Object -Skip 1) {
        $null = Invoke-TestApi POST "/language-learning/practice/questions/$($question.questionId)/answers" @{answer=@('A')} $headers
    }
    $completed = Invoke-TestApi GET "/language-learning/practice/sets/$($set.practiceSetId)" $null $headers
    if ($completed.status -ne 'COMPLETED' -or $completed.officialScore -ne 80 -or $completed.answeredCount -ne 5) { throw 'Official first-attempt score changed' }
    $history = Invoke-TestApi GET '/language-learning/history?source=READING&period=30d' $null $headers
    $historyItem = @($history | Where-Object { $_.activityId -eq "READING:$($set.practiceSetId)" })
    if ($historyItem.Count -ne 1 -or $historyItem[0].overallScore -ne 80) { throw 'LL Reading report missing from Core history' }

    # 저장 검증: 공식 첫 시도·retry·Growth는 LL에만 있고 Core의 옛 Practice 테이블은 없다.
    $entry = docker inspect --format '{{json .Config.Env}}' $container | ConvertFrom-Json |
        Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' } | Select-Object -First 1
    $dbPassword = $entry.Substring('MYSQL_ROOT_PASSWORD='.Length)
    $localSetId = -[long]$set.practiceSetId
    $userId = [long]$learner.Id
    $sql = "SELECT CONCAT((SELECT COUNT(*) FROM language_learning_practice_attempt a JOIN language_learning_practice_question q ON q.id=a.question_id WHERE q.practice_set_id=$localSetId),':',(SELECT COUNT(*) FROM language_learning_activity WHERE user_id=$userId AND source='READING' AND reference_id='ll-practice-$localSetId' AND overall_score=80),':',(SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='translacat_be_it_live_7cb72f72' AND TABLE_NAME='language_learning_practice_set'));"
    $stored = docker exec -e "MYSQL_PWD=$dbPassword" $container mysql -uroot $catalog --batch --skip-column-names -e $sql
    if ($LASTEXITCODE -ne 0 -or $stored.Trim() -ne '6:1:0') { throw 'LL/Core ownership or Growth persistence mismatch' }

    # 실패 검증: p2 검증 응답만 손상시켜 기존 p1 문항과 답변이 두 차례 실패에도 유지되는지 확인한다.
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    try {
        [IO.File]::WriteAllText($control, '{"mode":"CONTEXT_INFERENCE","failure":"P2_VERIFIER_BINDING"}')
        $partial = Invoke-TestApi GET '/language-learning/practice/today?domain=READING&mode=CONTEXT_INFERENCE' $null $headers
        $partial = Wait-Practice $partial.practiceSetId $headers
        if ($partial.generationStatus -ne 'PARTIAL' -or $partial.questions.Count -ne 3 -or $partial.generationFailureMessage -ne 'VERIFIER_SCHEMA_INVALID') { throw "Malformed verifier state=$($partial.generationStatus) failure=$($partial.generationFailureMessage)" }
        $null = Invoke-TestApi POST "/language-learning/practice/questions/$($partial.questions[0].questionId)/answers" @{answer=@('B')} $headers
        $beforeRetry = Invoke-TestApi GET "/language-learning/practice/sets/$($partial.practiceSetId)" $null $headers
        $originalQuestions = $beforeRetry.questions | ConvertTo-Json -Depth 20 -Compress
        $null = Invoke-TestApi POST "/language-learning/practice/sets/$($partial.practiceSetId)/retry-generation" @{} $headers
        $afterRetry = Wait-Practice $partial.practiceSetId $headers
        if ($afterRetry.generationStatus -ne 'PARTIAL' -or ($afterRetry.questions | ConvertTo-Json -Depth 20 -Compress) -ne $originalQuestions) { throw 'Failed retry changed existing question or answer' }
        [IO.File]::WriteAllText($control, '{}')
        $null = Invoke-TestApi POST "/language-learning/practice/sets/$($partial.practiceSetId)/retry-generation" @{} $headers
        $recovered = Wait-Practice $partial.practiceSetId $headers
        if ($recovered.generationStatus -ne 'READY' -or $recovered.questions.Count -ne 5 -or $recovered.answeredCount -ne 1 -or $recovered.questions[0].attempts[0].answer[0] -ne 'B') { throw 'Recovery lost existing answer' }
    } finally {
        if (Test-Path -LiteralPath $control) { Remove-Item -LiteralPath $control }
    }
    Write-Output 'PASS: actual BE/LL/Python/DB Reading generation, answer retry policy, duplicate, ownership, legacy ID, retired Vocabulary, verifier failure and data-preserving recovery'
}
$after = if (Test-Path -LiteralPath $statsFile) { Get-Content -Raw -LiteralPath $statsFile | ConvertFrom-Json -AsHashtable } else { @{} }
$delta = @{}
foreach ($key in $after.Keys) { $delta[$key] = [int]$after[$key] - [int]$before[$key] }
Write-Output ("Synthetic model calls=" + (($delta.Values | Measure-Object -Sum).Sum) + " stages=" + ($delta | ConvertTo-Json -Compress) + " elapsedSeconds=" + [math]::Round(((Get-Date)-$started).TotalSeconds,2) + ' paidCalls=0')
