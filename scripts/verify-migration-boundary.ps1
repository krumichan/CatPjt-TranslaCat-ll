$ErrorActionPreference = 'Stop'
$llRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$beRoot = [IO.Path]::GetFullPath((Join-Path $llRoot '../CatPjt-TranslaCat-be'))
$aiRoot = [IO.Path]::GetFullPath((Join-Path $llRoot '../CatPjt-TranslaCat-ai'))
$findings = [Collections.Generic.List[object]]::new()

function Find-SourceBoundary($files, $pattern, $rule) {
    # 소스 위치와 규칙만 기록한다. 일치한 문자열에는 비밀값이나 원문이 있을 수 있어 출력하지 않는다.
    foreach ($file in $files) {
        $line = 0
        foreach ($text in [IO.File]::ReadLines($file.FullName)) {
            $line++
            if ($text -match $pattern) {
                $findings.Add([pscustomobject]@{rule=$rule; file=$file.FullName; line=$line})
            }
        }
    }
}

# 준비: 명시된 세 로컬 저장소의 운영 소스만 검사하며 테스트 fixture와 원본 백업은 제외한다.
$llSources = @(Get-ChildItem -LiteralPath (Join-Path $llRoot 'src/main/kotlin') -Recurse -File -Filter '*.kt')
$aiSources = @(Get-ChildItem -LiteralPath (Join-Path $aiRoot 'app') -Recurse -File -Filter '*.py')
$beLearning = @(Get-ChildItem -LiteralPath (Join-Path $beRoot 'src/main/java/jp/co/translacat/domain/languagelearning') -Recurse -File -Filter '*.java')
if ($llSources.Count -eq 0 -or $aiSources.Count -eq 0 -or $beLearning.Count -eq 0) { throw 'Expected local source roots' }

# 실행: LL의 실행 transport와 데이터 원본, Python 업무 import, BE 구 저장·worker 경계를 독립 검사한다.
Find-SourceBoundary $llSources '(api\.openai\.com|api\.anthropic\.com|generativelanguage\.googleapis\.com|^import (com\.openai|com\.anthropic|com\.google\.genai)\.|ProcessBuilder\(|Runtime\.getRuntime\(|translacat_be_it_|jdbc:log4jdbc:)' 'LL_DIRECT_PROVIDER_OR_CORE'
Find-SourceBoundary $aiSources '(from|import)\s+app\.(features\.language_learning|schemas\.language_learning|api\.v1\.language_learning)' 'PYTHON_LEARNING_BUSINESS_IMPORT'
Find-SourceBoundary $beLearning '(@Scheduled\b|extends\s+JpaRepository|@Entity\b)' 'BE_LEARNING_WORKER_OR_ENTITY'

$oldPython = Join-Path $aiRoot 'app/features/language_learning'
if (Test-Path -LiteralPath $oldPython) {
    foreach ($file in Get-ChildItem -LiteralPath $oldPython -Recurse -File -Filter '*.py') {
        if ($file.Name -ne '__init__.py') {
            $findings.Add([pscustomobject]@{rule='PYTHON_LEARNING_BUSINESS_MODULE'; file=$file.FullName; line=0})
        }
    }
}
$oldBatch = Join-Path $beRoot 'src/main/java/jp/co/translacat/batch/languagelearning'
if (Test-Path -LiteralPath $oldBatch) {
    foreach ($file in Get-ChildItem -LiteralPath $oldBatch -Recurse -File -Filter '*.java') {
        $findings.Add([pscustomobject]@{rule='BE_LEARNING_BATCH'; file=$file.FullName; line=0})
    }
}
$aiClient = Get-Item -LiteralPath (Join-Path $beRoot 'src/main/java/jp/co/translacat/infrastructure/client/ai/server/AiServerClient.java')
Find-SourceBoundary @($aiClient) '/(?:api/v1/)?language-learning/' 'BE_OLD_AI_BUSINESS_ENDPOINT'

# 검증: 정적 참조 검사는 실제 HTTP/DB/프로세스 검증과 별도이며 잔여를 성공으로 바꾸지 않는다.
Write-Output "Scanned LL=$($llSources.Count), AI=$($aiSources.Count), BE learning=$($beLearning.Count); violations=$($findings.Count)"
if ($findings.Count -gt 0) {
    $findings | ConvertTo-Json -Depth 3
    exit 1
}
Write-Output 'PASS: static migration boundary; live HTTP/DB verification is recorded separately'
