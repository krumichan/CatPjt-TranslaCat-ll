package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings

suspend fun Application.configurePersistence() {
    val settings = dependencies.resolve<DatabaseSettings>()
    if (!settings.enabled) {
        environment.log.info("Database persistence is disabled; pool and migrations were not started.")
        return
    }

    dependencies {
        // AutoCloseable의 종료는 Ktor DI가 소유한다. 중복 종료 핸들러를 등록하지 않는다.
        provide<DatabaseFactory> { DatabaseFactory(settings) }
    }

    // migration이 실패하거나 미적용 상태이면 요청을 받기 전에 기동을 중단한다.
    // JDBC 초기화는 기동 시 한 번 실행하며 HTTP 핸들러나 업무 트랜잭션에 넣지 않는다.
    val factory = dependencies.resolve<DatabaseFactory>()

    configureSettingsPersistence(factory, settings)
    configureKeywordPersistence()

    environment.log.info(
        "Database persistence initialized. catalog={} mode={} schemaVersion={} migrationsExecuted={}",
        settings.expectedCatalog,
        factory.migrationReport.mode,
        factory.migrationReport.schemaVersion,
        factory.migrationReport.migrationsExecuted,
    )
}
