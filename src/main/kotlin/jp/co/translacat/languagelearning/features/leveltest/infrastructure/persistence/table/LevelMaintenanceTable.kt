package jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/** 한 LL 배포의 자동 문제풀 보충을 한 작업자로 제한하는 lease다. */
internal object LevelMaintenanceTable : Table("language_learning_level_test_maintenance") {
    val id = varchar("id", 30)
    val token = varchar("claim_token", 36).nullable()
    val leaseUntil = datetime("lease_until").nullable()
    override val primaryKey = PrimaryKey(id)
}
