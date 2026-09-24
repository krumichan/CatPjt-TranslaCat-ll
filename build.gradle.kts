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
    implementation(libs.hikari)
    implementation(libs.mysql.connector)

    implementation(libs.logback.classic)

    testImplementation(kotlin("test-junit"))
    testImplementation(ktorLibs.server.testHost)
}


// The existing tests and the explicit database suite both use kotlin.test + JUnit 4.
tasks.withType<Test>().configureEach {
    useJUnit()
}

tasks.test {
    exclude("**/DatabaseMigrationIntegrationTest*")
}

// Explicit opt-in. Regular clean test/check never contacts MySQL.
// Creates/drops only random translacat_ll_it_<hex> databases on loopback MySQL.
tasks.register<Test>("databaseIntegrationTest") {
    group = "verification"
    description = "Verify Flyway/schema/seeds on disposable databases in local MySQL."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/DatabaseMigrationIntegrationTest*")
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
