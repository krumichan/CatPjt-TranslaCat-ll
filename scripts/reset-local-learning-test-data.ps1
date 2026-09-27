param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$evidence = [IO.Path]::GetFullPath((Join-Path $root '../.codex-workspace/verification/ll/final-cleanup'))
$container = '9d6e91ffcea2'
$coreCatalog = 'translacat_be_it_live_7cb72f72'
$llCatalog = 'translacat_ll_it_live_7cb72f72'

# 준비: 실제 소유 컨테이너·catalog와 정지한 서비스만 대상으로 한다.
$details = docker inspect $container | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or $details.Config.Image -ne 'mysql:8.4' -or
    $details.NetworkSettings.Ports.'3306/tcp'[0].HostIp -ne '127.0.0.1' -or
    $details.NetworkSettings.Ports.'3306/tcp'[0].HostPort -ne '33316') { throw 'Unowned scratch MySQL' }
if (@(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {
    $_.LocalPort -in @(18766, 18767)
}).Count -ne 0) { throw 'Stop the owned BE and LL before the audit/reset' }
$passwordEntry = @($details.Config.Env | Where-Object { $_ -like 'MYSQL_ROOT_PASSWORD=*' })
if ($passwordEntry.Count -ne 1) { throw 'Missing scratch credential' }
$password = $passwordEntry[0].Substring('MYSQL_ROOT_PASSWORD='.Length)
New-Item -ItemType Directory -Path $evidence -Force | Out-Null

function Invoke-LocalSql([string]$sql) {
    $result = @(docker exec -e "MYSQL_PWD=$password" $container mysql -uroot --batch --skip-column-names -e $sql)
    if ($LASTEXITCODE -ne 0) { throw 'Owned scratch SQL failed' }
    return $result
}

# 대상은 원본 업무 소유권·실제 외래키를 대조한 명시 목록이다. 접두사 검색으로 삭제하지 않는다.
$coreTables = @(
    'language_learning_daily_item', 'language_learning_daily_set', 'language_learning_generation_fingerprint',
    'language_learning_growth_outbox', 'language_learning_growth_outbox_stream',
    'language_learning_listening_daily_set', 'language_learning_listening_evaluation_report',
    'language_learning_listening_item', 'language_learning_listening_item_attempt', 'language_learning_listening_metric_history',
    'language_learning_listening_outbox', 'language_learning_listening_playback_event', 'language_learning_listening_session',
    'language_learning_listening_task_evaluation', 'language_learning_listening_task_response',
    'language_learning_practice_attempt', 'language_learning_practice_metric_score', 'language_learning_practice_question',
    'language_learning_practice_set', 'language_learning_recommendation', 'language_learning_speaking_ai_usage',
    'language_learning_speaking_coaching_result', 'language_learning_speaking_evaluation', 'language_learning_speaking_evaluation_job',
    'language_learning_speaking_evaluation_metric', 'language_learning_speaking_read_aloud_problem_evaluation',
    'language_learning_speaking_session', 'language_learning_speaking_topic', 'language_learning_speaking_turn',
    'language_learning_stt_error_report', 'language_learning_vocabulary_mastery',
    'language_learning_writing_answer', 'language_learning_writing_evaluation'
)
$llPreserve = @('flyway_schema_history', 'language_learning_admin_setting', 'language_learning_listening_policy_setting',
    'language_learning_keyword_catalog_lock', 'language_learning_level_test_maintenance',
    'language_learning_system_keyword', 'language_learning_system_keyword_locale', 'language_learning_speaking_topic')
$llClear = @(
    'language_learning_activity', 'language_learning_admin_setting_audit', 'language_learning_custom_keyword',
    'language_learning_daily_item', 'language_learning_daily_set', 'language_learning_generation_fingerprint',
    'language_learning_growth_operation', 'language_learning_growth_receipt', 'language_learning_growth_stream',
    'language_learning_keyword_mastery', 'language_learning_learner', 'language_learning_level_test_audio',
    'language_learning_level_test_baseline', 'language_learning_level_test_evaluation', 'language_learning_level_test_item',
    'language_learning_level_test_question_candidate', 'language_learning_level_test_question_pool',
    'language_learning_level_test_response', 'language_learning_level_test_session', 'language_learning_listening_audio',
    'language_learning_listening_job', 'language_learning_listening_metric_history', 'language_learning_listening_recommendation',
    'language_learning_listening_record_id', 'language_learning_listening_session', 'language_learning_listening_set',
    'language_learning_metric_history', 'language_learning_practice_attempt', 'language_learning_practice_metric',
    'language_learning_practice_question', 'language_learning_practice_set', 'language_learning_profile',
    'language_learning_profile_evidence', 'language_learning_profile_signal', 'language_learning_result_event',
    'language_learning_result_stream', 'language_learning_settings_selection_delivery', 'language_learning_speaking_ai_usage',
    'language_learning_speaking_audio', 'language_learning_speaking_evaluation_job', 'language_learning_speaking_result',
    'language_learning_speaking_session', 'language_learning_speaking_turn', 'language_learning_stt_error_report',
    'language_learning_user_setting', 'language_learning_user_system_keyword', 'language_learning_vocabulary_mastery',
    'language_learning_writing_answer', 'language_learning_writing_evaluation'
)
$metadata = @(Invoke-LocalSql "SELECT TABLE_SCHEMA,TABLE_NAME,COALESCE(AUTO_INCREMENT,0) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('$coreCatalog','$llCatalog') ORDER BY TABLE_SCHEMA,TABLE_NAME;" |
    ForEach-Object { $parts = $_ -split "`t"; [pscustomobject]@{catalog=$parts[0];table=$parts[1];nextId=[long]$parts[2]} })
$foreignKeys = @(Invoke-LocalSql "SELECT TABLE_SCHEMA,TABLE_NAME,REFERENCED_TABLE_SCHEMA,REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE REFERENCED_TABLE_NAME IS NOT NULL AND (TABLE_SCHEMA IN ('$coreCatalog','$llCatalog') OR REFERENCED_TABLE_SCHEMA IN ('$coreCatalog','$llCatalog'));" |
    ForEach-Object { $parts = $_ -split "`t"; [pscustomobject]@{catalog=$parts[0];table=$parts[1];parentCatalog=$parts[2];parent=$parts[3]} })
if (@($metadata | Where-Object { $_.catalog -eq $coreCatalog }).Count -eq 0 -or
    @($metadata | Where-Object { $_.catalog -eq $llCatalog }).Count -eq 0) { throw 'Expected catalogs absent' }
if (@($metadata | Where-Object { $_.catalog -eq $llCatalog -and $_.table -notin ($llClear + $llPreserve) }).Count -gt 0) {
    throw 'New LL tables require an explicit ownership review before reset'
}
$corePresent = @($metadata | Where-Object { $_.catalog -eq $coreCatalog -and $_.table -in $coreTables } | ForEach-Object table)
$llPresent = @($metadata | Where-Object { $_.catalog -eq $llCatalog -and $_.table -in $llClear } | ForEach-Object table)
if ($corePresent.Count -ne $coreTables.Count -or $llPresent.Count -ne $llClear.Count) { throw 'Expected initial table set differs; do not reapply blindly' }

function Get-ChildFirstOrder([string]$catalog, [string[]]$targets) {
    $external = @($foreignKeys | Where-Object {
        $_.parentCatalog -eq $catalog -and $_.parent -in $targets -and
        ($_.catalog -ne $catalog -or $_.table -notin $targets)
    })
    if ($external.Count -ne 0) { throw 'Preserved or external tables reference a reset target' }
    $pending = [Collections.Generic.List[string]]::new()
    $targets | ForEach-Object { $pending.Add($_) }
    $ordered = [Collections.Generic.List[string]]::new()
    while ($pending.Count -gt 0) {
        $leaf = @($pending | Where-Object {
            $candidate = $_
            @($foreignKeys | Where-Object { $_.parentCatalog -eq $catalog -and $_.parent -eq $candidate -and $_.table -in $pending }).Count -eq 0
        } | Select-Object -First 1)
        if ($leaf.Count -ne 1) { throw 'Unexpected foreign key cycle' }
        $ordered.Add($leaf[0]); $null = $pending.Remove($leaf[0])
    }
    return $ordered.ToArray()
}
$coreOrder = @(Get-ChildFirstOrder $coreCatalog $corePresent)
$llOrder = @(Get-ChildFirstOrder $llCatalog $llPresent)
$preserved = @($metadata | Where-Object {
    ($_.catalog -eq $coreCatalog -and $_.table -notin $coreTables) -or
    ($_.catalog -eq $llCatalog -and $_.table -in $llPreserve)
})
function Get-PreservedChecksums {
    $queries = @($preserved | ForEach-Object { "CHECKSUM TABLE $($_.catalog).$($_.table);" })
    return @(Invoke-LocalSql ($queries -join "`n"))
}
$beforeChecksums = @(Get-PreservedChecksums)
$counts = @()
foreach ($catalog in @($coreCatalog, $llCatalog)) {
    $targets = if ($catalog -eq $coreCatalog) { $coreOrder } else { $llOrder }
    foreach ($table in $targets) {
        $rowCount = [long]@(Invoke-LocalSql "SELECT COUNT(*) FROM $catalog.$table;")[0]
        $counts += [pscustomobject]@{catalog=$catalog;table=$table;rows=$rowCount;action=$(if($catalog -eq $coreCatalog){'DROP legacy table'}else{'DELETE test rows; preserve nextId'})}
    }
}

# 이전 ID만 기록한다. 답변·오디오·토큰·사용자 원문은 증거에 복사하지 않는다.
$oldReferences = @()
foreach ($table in @('language_learning_daily_set','language_learning_practice_set','language_learning_listening_set',
    'language_learning_speaking_session','language_learning_level_test_session')) {
    $oldReferences += @(Invoke-LocalSql "SELECT '$table',id,user_id FROM $llCatalog.$table ORDER BY id DESC LIMIT 2;")
}
$plan = [ordered]@{container=$container;host='127.0.0.1:33316';coreCatalog=$coreCatalog;llCatalog=$llCatalog;
    tableActions=$counts;preserved=$preserved;autoIncrement=$metadata;oldReferences=$oldReferences;foreignKeys=$foreignKeys}
[IO.File]::WriteAllText((Join-Path $evidence 'data-reset-plan.json'), ($plan | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllLines((Join-Path $evidence 'preserved-checksums-before.tsv'), $beforeChecksums, [Text.UTF8Encoding]::new($false))
Write-Output "Reviewed plan: Core DROP $($coreOrder.Count); LL DELETE $($llOrder.Count); preserve $($preserved.Count) tables; no Redis/file deletion"
if (-not $Apply) { return }

# 실행: 자식부터 삭제하며 FK 검사를 끄지 않는다. 적용 migration과 ID 할당 상한은 보존한다.
$llStatements = @('START TRANSACTION;') + @($llOrder | ForEach-Object { "DELETE FROM $llCatalog.$_;" }) + @('COMMIT;')
$null = Invoke-LocalSql ($llStatements -join "`n")
$null = Invoke-LocalSql (@($coreOrder | ForEach-Object { "DROP TABLE $coreCatalog.$_;" }) -join "`n")

# 검증: 다른 제품·인증·seed가 동일하고 초기화된 ID가 이전 값으로 돌아가지 않았는지 확인한다.
$afterChecksums = @(Get-PreservedChecksums)
if (($beforeChecksums -join "`n") -ne ($afterChecksums -join "`n")) { throw 'Preserved data checksum changed' }
foreach ($table in $llOrder) {
    if ([long]@(Invoke-LocalSql "SELECT COUNT(*) FROM $llCatalog.$table;")[0] -ne 0) { throw 'LL test rows remain' }
}
$afterMetadata = @(Invoke-LocalSql "SELECT TABLE_NAME,COALESCE(AUTO_INCREMENT,0) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$llCatalog';")
foreach ($line in $afterMetadata) {
    $parts = $line -split "`t"
    $old = $metadata | Where-Object { $_.catalog -eq $llCatalog -and $_.table -eq $parts[0] }
    if ([long]$parts[1] -lt $old.nextId) { throw 'Auto increment high-water decreased' }
}
$coreRemaining = @(Invoke-LocalSql "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='$coreCatalog';")
if (@($coreRemaining | Where-Object { $_ -in $coreTables }).Count -ne 0) { throw 'Legacy Core tables remain' }
[IO.File]::WriteAllLines((Join-Path $evidence 'preserved-checksums-after.tsv'), $afterChecksums, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $evidence 'data-reset-result.json'), (@{applied=$true;coreRemoved=$coreOrder.Count;llCleared=$llOrder.Count;
    preservedChecksumsMatch=$true;highWaterPreserved=$true;discardedLegacyEvents='Deleted as test data; no ACK or success marking';paidCalls=0} | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
Write-Output 'PASS: legacy Core tables absent; LL test rows cleared; shared data/seed/checksum/high-water preserved; no ACK fabricated'
