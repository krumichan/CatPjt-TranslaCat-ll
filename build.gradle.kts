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

    implementation(libs.logback.classic)

    testImplementation(kotlin("test-junit"))
    testImplementation(ktorLibs.server.testHost)
}


// 일반 테스트와 명시적 DB 테스트 모두 kotlin.test + JUnit 4를 사용한다.
tasks.withType<Test>().configureEach {
    useJUnit()
}

tasks.test {
    exclude("**/DatabaseMigrationIntegrationTest*", "**/SettingsPersistenceIntegrationTest*", "**/SettingsFeatureIntegrationTest*", "**/SettingsCutoverIntegrationTest*", "**/KeywordCatalogIntegrationTest*", "**/ResultJournalIntegrationTest*", "**/LevelTestPersistenceIntegrationTest*", "**/GrowthPersistenceIntegrationTest*")
}

// 명시적으로 실행할 때만 MySQL을 사용한다. 일반 test/check에는 포함하지 않는다.
// loopback MySQL의 무작위 translacat_ll_it_<hex> DB만 생성/정리한다.
tasks.register<Test>("databaseIntegrationTest") {
    group = "verification"
    description = "로컬 임시 DB에서 migration, Settings와 Keyword 저장·동시성을 검증합니다."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/DatabaseMigrationIntegrationTest*", "**/SettingsPersistenceIntegrationTest*", "**/SettingsFeatureIntegrationTest*", "**/SettingsCutoverIntegrationTest*", "**/KeywordCatalogIntegrationTest*", "**/ResultJournalIntegrationTest*", "**/LevelTestPersistenceIntegrationTest*", "**/GrowthPersistenceIntegrationTest*")
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
