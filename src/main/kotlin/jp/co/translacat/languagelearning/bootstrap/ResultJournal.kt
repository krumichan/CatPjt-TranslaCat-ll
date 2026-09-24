package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.resultjournal.api.resultJournalRoutes
import jp.co.translacat.languagelearning.features.resultjournal.application.AcceptLearningResult
import jp.co.translacat.languagelearning.features.resultjournal.infrastructure.persistence.ExposedResultJournalUnitOfWork
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner

/** 기본은 비활성이다. 기존 FE 조회/집계 경로는 이 원장을 사용하지 않는다. */
internal suspend fun Application.configureResultJournal() {
    val config = environment.config
    val enabled = config.propertyOrNull("resultJournal.enabled")?.getString()?.toBooleanStrict() ?: false
    if (!enabled) return
    check(loadInternalApiSettings().enabled && dependencies.resolve<DatabaseSettings>().enabled) {
        "결과 수신 원장에는 DB와 내부 JWT 인증이 필요합니다."
    }
    val source = config.property("resultJournal.sourceInstanceId").getString()
    val transactions = dependencies.resolve<JdbcTransactionRunner>()
    val accept = AcceptLearningResult(ExposedResultJournalUnitOfWork(transactions), source)
    routing { resultJournalRoutes(accept) }
}
