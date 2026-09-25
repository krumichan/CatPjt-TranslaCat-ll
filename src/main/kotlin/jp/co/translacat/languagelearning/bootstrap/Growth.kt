package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.growth.api.growthRoutes
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.ExposedGrowthUnitOfWork
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.table.GrowthStreamsTable
import jp.co.translacat.languagelearning.shared.persistence.DatabaseSettings
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.*

/** 배포 전환은 명시적으로 켠다. 비활성 중에도 LL Level Test 완료와 V008 기준점은 로컬에 보존한다. */
internal suspend fun Application.configureGrowth() {
    val config = environment.config
    if (!(config.propertyOrNull("growth.enabled")?.getString()?.toBooleanStrict() ?: false)) return
    check(loadInternalApiSettings().enabled && dependencies.resolve<DatabaseSettings>().enabled) {
        "성장 이행에는 LL DB와 내부 JWT 인증이 필요합니다."
    }
    val source = config.property("growth.sourceInstanceId").getString()
    require(UUID.fromString(source).toString() == source) { "growth.sourceInstanceId는 고정된 UUID여야 합니다." }
    val transactions = dependencies.resolve<JdbcTransactionRunner>()
    transactions.read {
        check(GrowthStreamsTable.selectAll().where { GrowthStreamsTable.sourceId neq source }.limit(1).empty()) {
            "이미 수신한 성장 데이터의 source UUID를 변경할 수 없습니다."
        }
    }
    routing { growthRoutes(ExposedGrowthUnitOfWork(transactions), source) }
    environment.log.info("Growth internal API initialized. schema=008")
}
