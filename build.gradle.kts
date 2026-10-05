plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "jp.co.translacat"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}
dependencies {
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.callId)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    // 기존 Ktor catalog와 정확히 같은 버전으로 JWT 어댑터를 추가한다.
    val ktorVersion = ktorLibs.server.core.get().versionConstraint.requiredVersion
    require(ktorVersion.isNotBlank()) { "Ktor catalog의 버전을 확인해 주세요." }
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation(ktorLibs.server.di)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.requestValidation)
    implementation(ktorLibs.server.routingOpenapi)
    implementation(ktorLibs.server.statusPages)
    implementation(ktorLibs.server.swagger)

    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hikari)
    implementation(libs.mysql.connector)

    // 기존 Core와 같은 고정 SDK로 Speaking의 S3 호환 저장 경계를 이행한다.
    implementation(platform("software.amazon.awssdk:bom:2.46.21"))
    implementation("software.amazon.awssdk:s3")

    implementation(libs.logback.classic)

    testImplementation(kotlin("test-junit"))
    testImplementation(ktorLibs.server.testHost)
}


// 일반 테스트와 명시적 DB 테스트 모두 kotlin.test + JUnit 4를 사용한다.
tasks.withType<Test>().configureEach {
    useJUnit()
}

tasks.test {
    // 큐레이션의 실제 MySQL 검증도 전용 task에서 실행한다. 이미지의 일반 test에는 DB를 주입하지 않는다.
    exclude("**/CuratedWritingStoreIntegrationTest*", "**/CuratedWritingHttpDatabaseIntegrationTest*")
    exclude("**/LevelTestHttpDatabaseIntegrationTest*")
    exclude("**/SpeakingConversationHttpIntegrationTest*")
    exclude("**/SpeakingEvaluationHttpIntegrationTest*")
    exclude("**/DatabaseMigrationIntegrationTest*", "**/SettingsPersistenceIntegrationTest*", "**/SettingsFeatureIntegrationTest*", "**/SettingsCutoverIntegrationTest*", "**/KeywordCatalogIntegrationTest*", "**/LevelTestPersistenceIntegrationTest*", "**/GrowthPersistenceIntegrationTest*", "**/WritingSchemaIntegrationTest*", "**/WritingSetStateIntegrationTest*", "**/PracticeStateIntegrationTest*", "**/SpeakingStateIntegrationTest*", "**/ListeningStateIntegrationTest*", "**/ModelExecutionHttpIntegrationTest*", "**/ListeningSpeechHttpIntegrationTest*", "**/LevelEvaluationHttpIntegrationTest*", "**/WritingHttpDatabaseIntegrationTest*")
}

tasks.register<Test>("aiHttpIntegrationTest") {
    group = "verification"
    description = "명시적으로 실행한 테스트 전용 Python AI HTTP 서버와 LL adapter 계약을 검증합니다."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/ModelExecutionHttpIntegrationTest*", "**/ListeningSpeechHttpIntegrationTest*", "**/LevelEvaluationHttpIntegrationTest*")
    include("**/SpeakingConversationHttpIntegrationTest*")
    include("**/SpeakingEvaluationHttpIntegrationTest*")
    outputs.upToDateWhen { false }
    doFirst {
        require(!System.getenv("LL_TEST_AI_URL").isNullOrEmpty()) {
            "LL_TEST_AI_URL must point to the local test-only Python AI server."
        }
    }
}

tasks.register<Test>("writingHttpDatabaseIntegrationTest") {
    group = "verification"
    description = "로컬 MySQL 및 테스트 전용 Python Provider를 통한 Writing 평가 상태 경로를 검증합니다."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/WritingHttpDatabaseIntegrationTest*")
    include("**/CuratedWritingHttpDatabaseIntegrationTest*")
    outputs.upToDateWhen { false }
    doFirst {
        listOf("LL_TEST_AI_URL", "LL_TEST_MYSQL_URL", "LL_TEST_MYSQL_USERNAME", "LL_TEST_MYSQL_PASSWORD").forEach { name ->
            require(!System.getenv(name).isNullOrEmpty()) { "$name must be set for writingHttpDatabaseIntegrationTest." }
        }
    }
}

tasks.register<Test>("levelTestHttpDatabaseIntegrationTest") {
    group = "verification"
    description = "실제 Python 범용 HTTP와 로컬 MySQL을 사용하는 Level Test 전체 업무 경로를 검증합니다."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/LevelTestHttpDatabaseIntegrationTest*")
    outputs.upToDateWhen { false }
    doFirst {
        listOf("LL_TEST_AI_URL", "LL_TEST_MYSQL_URL", "LL_TEST_MYSQL_USERNAME", "LL_TEST_MYSQL_PASSWORD").forEach { name ->
            require(!System.getenv(name).isNullOrEmpty()) { "$name must be set for levelTestHttpDatabaseIntegrationTest." }
        }
    }
}

// 명시적으로 실행할 때만 MySQL을 사용한다. 일반 test/check에는 포함하지 않는다.
// loopback MySQL의 무작위 translacat_ll_it_<hex> DB만 생성/정리한다.
tasks.register<Test>("databaseIntegrationTest") {
    group = "verification"
    description = "로컬 임시 DB에서 migration, Settings와 Keyword 저장·동시성을 검증합니다."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/CuratedWritingStoreIntegrationTest*")
    include("**/DatabaseMigrationIntegrationTest*", "**/SettingsPersistenceIntegrationTest*", "**/SettingsFeatureIntegrationTest*", "**/SettingsCutoverIntegrationTest*", "**/KeywordCatalogIntegrationTest*", "**/LevelTestPersistenceIntegrationTest*", "**/GrowthPersistenceIntegrationTest*", "**/WritingSchemaIntegrationTest*", "**/WritingSetStateIntegrationTest*", "**/PracticeStateIntegrationTest*", "**/SpeakingStateIntegrationTest*", "**/ListeningStateIntegrationTest*")
    shouldRunAfter(tasks.test)
    outputs.upToDateWhen { false }
    doFirst {
        listOf("LL_TEST_MYSQL_URL", "LL_TEST_MYSQL_USERNAME", "LL_TEST_MYSQL_PASSWORD").forEach { name ->
            require(!System.getenv(name).isNullOrEmpty()) {
                "$name must be set for databaseIntegrationTest. See docs/database-foundation.md."
            }
        }
    }
}
