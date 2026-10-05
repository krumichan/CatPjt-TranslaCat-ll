param(
    [string]$ConfigurationFile = (Join-Path $PSScriptRoot '../.local/development.json'),
    [switch]$Migrate,
    [switch]$NoBuild,
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$allowed = @('DB_JDBC_URL', 'DB_USERNAME', 'DB_PASSWORD_FILE', 'DB_MIGRATOR_USERNAME',
    'DB_MIGRATOR_PASSWORD_FILE', 'LL_INTERNAL_JWT_SECRET_BASE64_FILE', 'AI_SERVER_URL',
    'AI_SERVER_API_KEY_FILE', 'LL_LEVEL_TEST_AUDIO_UPLOAD_BASE_URL', 'LL_SPEAKING_TTS_MODEL', 'LL_PORT',
    'LL_LEVEL_TEST_PREFETCH_ENABLED')

# 비밀은 파일에서만 읽고 localhost 전용 대상과 서로 다른 DB 계정을 기동 전에 확인한다.
try {
    $configuration = [IO.File]::ReadAllText([IO.Path]::GetFullPath($ConfigurationFile)) | ConvertFrom-Json -AsHashtable
    if ($configuration -isnot [hashtable] -or @($configuration.Keys | Where-Object { $_ -cnotin $allowed }).Count) {
        throw 'Invalid configuration names.'
    }
    # 로컬 실모델 검증은 현재 문항만 생성해 불필요한 유료 후보 생성을 기본적으로 피한다.
    if (-not $configuration.ContainsKey('LL_LEVEL_TEST_PREFETCH_ENABLED')) {
        $configuration.LL_LEVEL_TEST_PREFETCH_ENABLED = 'false'
    }
    if ($configuration.LL_LEVEL_TEST_PREFETCH_ENABLED -cnotin @('true', 'false')) {
        throw 'Prefetch must be true or false.'
    }
    foreach ($name in $allowed) {
        if ($configuration[$name] -isnot [string] -or [string]::IsNullOrWhiteSpace($configuration[$name])) {
            throw 'A required configuration value is absent.'
        }
    }
    if ($configuration.DB_JDBC_URL -cnotmatch '^jdbc:mysql://(?:localhost|127\.0\.0\.1):3306/translacat_ll$') {
        throw 'Database target is not the Development catalog on loopback port 3306.'
    }
    foreach ($name in @('AI_SERVER_URL', 'LL_LEVEL_TEST_AUDIO_UPLOAD_BASE_URL')) {
        $uri = [uri]$configuration[$name]
        if (-not $uri.IsAbsoluteUri -or -not $uri.IsLoopback -or $uri.Scheme -cne 'http' -or
            $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -cne '/') {
            throw 'Service target is not a plain loopback HTTP origin.'
        }
    }
    if ($configuration.LL_PORT -notmatch '^\d+$' -or [int]$configuration.LL_PORT -lt 1024 -or
        [int]$configuration.LL_PORT -gt 65535 -or $configuration.DB_USERNAME -ceq $configuration.DB_MIGRATOR_USERNAME) {
        throw 'Invalid port or shared database account.'
    }
    $settings = @{}
    foreach ($name in $allowed) {
        if ($name.EndsWith('_FILE')) {
            $path = $configuration[$name]
            if (-not [IO.Path]::IsPathFullyQualified($path)) { throw 'Secret paths must be absolute.' }
            $value = [IO.File]::ReadAllText($path)
            if ([string]::IsNullOrWhiteSpace($value) -or $value.Contains("`r") -or $value.Contains("`n")) {
                throw 'Secret files must contain one literal value without a newline.'
            }
            $settings[$name.Substring(0, $name.Length - 5)] = $value
        } else { $settings[$name] = $configuration[$name] }
    }
    if ([Convert]::FromBase64String($settings.LL_INTERNAL_JWT_SECRET_BASE64).Length -lt 32) {
        throw 'Internal JWT signing key is too short.'
    }
} catch {
    throw 'LL Development configuration is invalid. Check required settings, loopback targets, separate DB accounts and secret files; values were not printed.'
}
if ($ValidateOnly) {
    Write-Output 'VERIFIED: LL Development local configuration and secret-file bindings. No database or server connection was started.'
    return
}

# 기존 Gradle wrapper와 설치된 JDK를 사용한다. 빌드 실패는 migration/서버 기동 전에 중단한다.
Push-Location -LiteralPath $repository
$previous = @{}
try {
    if (-not $NoBuild) {
        & (Join-Path $repository 'gradlew.bat') installDist --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'LL Development build failed.' }
    }
    $libraries = Join-Path $repository 'build/install/CatPjt-TranslaCat-ll/lib'
    if (-not (Test-Path -LiteralPath $libraries -PathType Container)) { throw 'Run the LL installDist build first.' }
    $settings.LL_ALLOW_MIGRATIONS = if ($Migrate) { 'true' } else { 'false' }
    if ($Migrate) {
        $settings.DB_USERNAME = $settings.DB_MIGRATOR_USERNAME
        $settings.DB_PASSWORD = $settings.DB_MIGRATOR_PASSWORD
    }
    $settings.Remove('DB_MIGRATOR_USERNAME')
    $settings.Remove('DB_MIGRATOR_PASSWORD')
    foreach ($name in $settings.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name)
        [Environment]::SetEnvironmentVariable($name, $settings[$name])
    }

    # 별도 migration 명령만 DDL 자격을 받고 일상 서버는 validate와 DML 계정을 사용한다.
    if ($Migrate) {
        & java -cp "$libraries/*" jp.co.translacat.languagelearning.bootstrap.MigrationMainKt
    } else {
        & java -cp "$libraries/*" io.ktor.server.netty.EngineMain `
            '-config=src/main/resources/application.yaml' '-config=config/application-development.yaml'
    }
    if ($LASTEXITCODE -ne 0) { throw 'LL Development process failed. Inspect the local application log.' }
} finally {
    foreach ($name in $previous.Keys) {
        $value = if ($null -eq $previous[$name]) { [NullString]::Value } else { $previous[$name] }
        [Environment]::SetEnvironmentVariable($name, $value)
    }
    Pop-Location
}
