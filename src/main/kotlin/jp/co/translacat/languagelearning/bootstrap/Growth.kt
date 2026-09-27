package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.growth.api.growthRoutes
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.ExposedGrowthUnitOfWork
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner

/** 현재 LL 업무 트랜잭션이 기록한 성장 자료를 사용자 인증 범위에서 조회한다. */
internal suspend fun Application.configureGrowth() {
    val config = environment.config
    if (!(config.propertyOrNull("growth.enabled")?.getString()?.toBooleanStrict() ?: false)) return
    check(loadInternalApiSettings().enabled && dependencies.resolve<DatabaseSettings>().enabled) {
        "성장 조회에는 LL DB와 내부 JWT 인증이 필요합니다."
    }
    val transactions = dependencies.resolve<JdbcTransactionRunner>()
    routing { growthRoutes(ExposedGrowthUnitOfWork(transactions)) }
    environment.log.info("Growth internal read API initialized.")
}
