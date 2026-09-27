$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18767/api/v1/language-learning/listening'
$container = '9d6e91ffcea2'
$catalog = 'translacat_ll_it_live_7cb72f72'
$testRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../CatPjt-TranslaCat-ai/.tmp_ktor_m0'))
$control = Join-Path $testRoot 'listening-control.json'
$marker = Join-Path $testRoot 'listening-hold.json'
if ((Test-Path -LiteralPath $control) -or (Test-Path -LiteralPath $marker)) { throw 'Another Listening control is active' }
if ($catalog -notmatch '^translacat_ll_it_live_[0-9a-f]{8}$') { throw 'Scratch catalog required' }
$started = Get-Date
$script:checks = 0
function Invoke-ListeningCrashApi($method, $path, $payload = $null) {
    $script:checks++
    $arguments = @{Uri="$base$path";Method=$method;Headers=$script:headers;SkipHttpErrorCheck=$true}
    if ($null -ne $payload) { $arguments.ContentType='application/json';$arguments.Body=$payload|ConvertTo-Json -Compress }
    $response = Invoke-WebRequest @arguments
    if ([int]$response.StatusCode -ne 200) { throw "Synthetic API failed: $method $path status=$($response.StatusCode)" }
    return ($response.Content|ConvertFrom-Json).body
}

# 준비: 실제 가입·로그인·설정을 거친 새 합성 계정에만 새 job을 만든다.
$identity=[guid]::NewGuid().ToString('N')
$account=@{email="lc-$identity@example.test";password='Synthetic-'+[guid]::NewGuid().ToString('N')}
$registered=Invoke-RestMethod 'http://127.0.0.1:18767/api/v1/auth/register' -Method Post -ContentType 'application/json' `
    -Body ((@{username='Synthetic Listening'}+$account)|ConvertTo-Json -Compress)
$login=Invoke-RestMethod 'http://127.0.0.1:18767/api/v1/auth/login' -Method Post -ContentType 'application/json' -Body ($account|ConvertTo-Json -Compress)
$script:headers=@{Authorization="Bearer $($login.body.accessToken)"}
[long]$userId=$registered.body.id
if ($userId -le 0) { throw 'Synthetic user missing' }
$null=Invoke-RestMethod 'http://127.0.0.1:18767/api/v1/language-learning/settings' -Method Patch -Headers $script:headers -ContentType 'application/json' `
    -Body '{"originLanguage":"ko","learningLanguage":"ja","timezone":"Asia/Seoul","dailyListeningGoalCount":1}'
$entry=docker inspect --format '{{json .Config.Env}}' $container | ConvertFrom-Json | Where-Object {$_ -like 'MYSQL_ROOT_PASSWORD=*'} | Select-Object -First 1
$dbPassword=$entry.Substring('MYSQL_ROOT_PASSWORD='.Length)

try {
    # 실행: 모델 HTTP가 진행 중인 시점을 관찰하고 실제 Ktor OS 프로세스를 종료한다.
    [IO.File]::WriteAllText($control,'{"failure":"HOLD_GENERATION"}')
    $set=Invoke-ListeningCrashApi POST '/daily-sets' @{itemCount=1}
    [long]$setId=-[long]$set.dailySetId
    $deadline=(Get-Date).AddSeconds(20)
    while (-not (Test-Path -LiteralPath $marker) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 100 }
    if (-not (Test-Path -LiteralPath $marker)) { throw 'Listening in-flight generation was not reached' }
    $sql="UPDATE language_learning_listening_job SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE user_id=$userId AND aggregate_id=$setId AND job_type='GENERATE' AND status='RUNNING' AND lease_token IS NOT NULL; SELECT ROW_COUNT();"
    & (Join-Path $PSScriptRoot 'restart-local-ktor.ps1') -BeforeStart {
        # 종료한 worker의 합성 job 하나만 만료시킨다. 전역 lease와 일반 데이터는 변경하지 않는다.
        $changed=docker exec -e "MYSQL_PWD=$dbPassword" $container mysql -uroot $catalog --batch --skip-column-names -e $sql
        if ($LASTEXITCODE -ne 0 -or $changed.Trim() -ne '1') { throw 'Expected exactly one synthetic Listening lease' }
        [IO.File]::WriteAllText($control,'{}')
    }

    # 검증: 재시도 API 없이 복구 worker가 같은 세트에 문항·오디오를 한 번만 게시한다.
    $deadline=(Get-Date).AddSeconds(30)
    do {
        $set=Invoke-ListeningCrashApi GET "/daily-sets/$(-$setId)"
        if ($set.status -eq 'READY') { break }
        Start-Sleep -Milliseconds 150
    } while ((Get-Date) -lt $deadline)
    if ($set.status -ne 'READY' -or $set.items.Count -ne 1 -or $set.physicalItemCount -ne 1) { throw 'Listening crash recovery did not publish exactly one item' }
    $sql="SELECT status,attempt_count FROM language_learning_listening_job WHERE user_id=$userId AND aggregate_id=$setId AND job_type='GENERATE'; SELECT COUNT(*) FROM language_learning_listening_audio WHERE user_id=$userId;"
    $facts=docker exec -e "MYSQL_PWD=$dbPassword" $container mysql -uroot $catalog --batch --skip-column-names -e $sql
    if ($LASTEXITCODE -ne 0 -or $facts[0] -ne "SUCCEEDED`t2" -or $facts[1] -ne '1') { throw 'Listening durable job facts differ after restart' }
    Write-Output "PASS Listening actual OS crash/recovery: same set, one item/audio, two claims, no retry API; HTTP=$script:checks elapsedSeconds=$([math]::Round(((Get-Date)-$started).TotalSeconds,2)) paidCalls=0"
} finally {
    if (Test-Path -LiteralPath $control) { Remove-Item -LiteralPath $control }
    if (Test-Path -LiteralPath $marker) { Remove-Item -LiteralPath $marker }
}
