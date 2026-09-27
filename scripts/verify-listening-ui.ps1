$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18767/api/v1'
$container = '9d6e91ffcea2'
$catalog = 'translacat_ll_it_live_7cb72f72'
if ($catalog -notmatch '^translacat_ll_it_live_[0-9a-f]{8}$') { throw 'Scratch catalog required' }

function Invoke-ListeningUiApi($method, $path, $payload, $headers = @{}) {
    $response = Invoke-WebRequest -Uri "$base$path" -Method $method -Headers $headers -ContentType 'application/json' `
        -Body ($payload | ConvertTo-Json -Compress) -SkipHttpErrorCheck
    if ([int]$response.StatusCode -ne 200) { throw "Synthetic API failed: $method $path status=$($response.StatusCode)" }
    return ($response.Content | ConvertFrom-Json).body
}

# 준비: 합성 계정과 실제 설정 API를 사용하고 테스트 DB에만 선행 레벨 기준점을 둔다.
$identity = [guid]::NewGuid().ToString('N')
$email = "lui-$identity@example.test"
$password = 'Synthetic-' + [guid]::NewGuid().ToString('N')
$registered = Invoke-ListeningUiApi POST '/auth/register' @{email=$email;password=$password;username='Synthetic Listening'}
$login = Invoke-ListeningUiApi POST '/auth/login' @{email=$email;password=$password}
$headers = @{Authorization="Bearer $($login.accessToken)"}
$null = Invoke-ListeningUiApi PATCH '/language-learning/settings' @{
    originLanguage='ko';learningLanguage='ja';timezone='Asia/Seoul';dailyListeningGoalCount=1
} $headers
[long]$userId = $registered.id
if ($userId -le 0) { throw 'Synthetic user missing' }
$entry = docker inspect --format '{{json .Config.Env}}' $container | ConvertFrom-Json |
    Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' } | Select-Object -First 1
$dbPassword = $entry.Substring('MYSQL_ROOT_PASSWORD='.Length)
$sql = @"
INSERT INTO language_learning_level_test_session (session_uid,user_id,session_type,status,origin_language,learning_language,timezone,current_question_number,current_complexity_band,base_level_score,proficiency_band,domain_scores_json,started_at,last_activity_at,completed_at,completed_date,idempotency_key)
VALUES(UUID(),$userId,'INITIAL','COMPLETED','ko','ja','Asia/Seoul',20,3,60,'INTERMEDIATE','{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_DATE(),'synthetic-listening-prerequisite');
SET @session=LAST_INSERT_ID();
INSERT INTO language_learning_level_test_baseline(user_id,session_id,completion_id,session_type,base_level_score,proficiency_band,completed_date,started_at,completed_at)
VALUES($userId,@session,UUID(),'INITIAL',60,'INTERMEDIATE',UTC_DATE(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO language_learning_profile(user_id,profile_version,state,base_level_score,evaluation_count,confidence,trend,additional_signals_json,created_at,updated_at,created_by,updated_by)
VALUES($userId,'PROFILE','ACTIVE',60,0,0.0,'stable','{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'TEST','TEST');
"@
docker exec -e "MYSQL_PWD=$dbPassword" $container mysql -uroot $catalog -e $sql
if ($LASTEXITCODE -ne 0) { throw 'Synthetic prerequisite insert failed' }

# 실행: 자격증명은 환경 변수에만 두고 실제 FE 페이지 검사를 실행한다.
$env:E2E_LISTENING_ACCESS_TOKEN = $login.accessToken
$env:E2E_LISTENING_REFRESH_TOKEN = $login.refreshToken
$env:E2E_LISTENING_PUBLIC_ID = $login.publicId
$env:NEXTAUTH_SECRET = 'synthetic-cutover-nextauth-secret-20260926'
$env:NEXTAUTH_URL = 'http://localhost:3000'
$env:NEXT_PUBLIC_API_URL = $base
$env:E2E_API_BASE_URL = $base
$env:E2E_BASE_URL = 'http://localhost:3000'
$env:PLAYWRIGHT_SKIP_WEB_SERVER = '0'
Push-Location (Join-Path $PSScriptRoot '../../CatPjt-TranslaCat-fe')
try {
    & 'C:\nvm4w\nodejs\node.exe' .\node_modules\@playwright\test\cli.js test e2e/integration/listening-cutover.spec.ts --project=integration-chromium --reporter=list
    if ($LASTEXITCODE -ne 0) { throw 'Listening browser verification failed' }
} finally { Pop-Location }
