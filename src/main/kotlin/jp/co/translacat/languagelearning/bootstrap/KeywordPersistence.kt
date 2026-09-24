package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import jp.co.translacat.languagelearning.features.keyword.application.DefaultKeywordOperations
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningDate
import jp.co.translacat.languagelearning.features.keyword.application.KeywordOperations
import jp.co.translacat.languagelearning.features.keyword.application.KeywordUnitOfWork
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.ExposedKeywordUnitOfWork
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner

internal suspend fun Application.configureKeywordPersistence() {
    val transactions = dependencies.resolve<JdbcTransactionRunner>()
    val settings = dependencies.resolve<SettingsServiceOperations>()
    val work = ExposedKeywordUnitOfWork(transactions)
    val dates = KeywordLearningDate { userId -> settings.userSnapshot(userId).learningDate }
    dependencies {
        provide<KeywordUnitOfWork> { work }
        provide<KeywordOperations> { DefaultKeywordOperations(work, dates) }
    }
}
