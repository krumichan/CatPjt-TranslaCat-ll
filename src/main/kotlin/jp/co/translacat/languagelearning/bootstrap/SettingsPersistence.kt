package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import jp.co.translacat.languagelearning.features.settings.application.GetOrCreateUserSettings
import jp.co.translacat.languagelearning.features.settings.application.SettingsUnitOfWork
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.ExposedSettingsUnitOfWork
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner

/** migration이 끝난 DB에만 연결한다. 등록 자체로 학습자/개인 설정을 생성하지 않는다. */
internal fun Application.configureSettingsPersistence(factory: DatabaseFactory, settings: DatabaseSettings) {
    val transactions = JdbcTransactionRunner(factory.database, settings.maximumPoolSize)
    val unitOfWork = ExposedSettingsUnitOfWork(transactions)
    val service = GetOrCreateUserSettings(unitOfWork)
    dependencies {
        provide<JdbcTransactionRunner> { transactions }
        provide<SettingsUnitOfWork> { unitOfWork }
        provide<GetOrCreateUserSettings> { service }
    }
}
