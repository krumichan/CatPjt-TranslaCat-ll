package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import jp.co.translacat.languagelearning.features.keyword.application.*
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.ExposedKeywordLearningFacts
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.ExposedKeywordUnitOfWork
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner

internal suspend fun Application.configureKeywordPersistence() {
    val transactions = dependencies.resolve<JdbcTransactionRunner>()
    val settings = dependencies.resolve<SettingsServiceOperations>()
    val work = ExposedKeywordUnitOfWork(transactions)
    val dates = KeywordLearningDate { userId -> settings.userSnapshot(userId).learningDate }
    dependencies {
        provide<KeywordUnitOfWork> { work }
        provide<KeywordOperations> {
            DefaultKeywordOperations(work, dates) { userId ->
                // 예약 언어가 아닌 현재 학습일에 활성화된 언어를 표시에도 사용한다.
                val user = settings.userSnapshot(userId).result.settings
                val origin = user.originLanguage
                val learning = user.learningLanguage
                if (origin != null && learning != null) ConfiguredLanguagePair(origin, learning) else null
            }
        }
        provide<KeywordLearningFacts> { ExposedKeywordLearningFacts(transactions) }
    }
}
