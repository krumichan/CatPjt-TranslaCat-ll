package jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.learner.domain.model.Learner
import jp.co.translacat.languagelearning.features.learner.domain.repository.LearnerRepository
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.table.LearnersTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.LocalDateTime

internal class ExposedLearnerRepository(private val requireTransaction: () -> Unit) : LearnerRepository {
    override fun ensureAndLock(userId: Long, nowUtc: LocalDateTime): Learner {
        requireTransaction()
        require(userId > 0)
        LearnersTable.upsert(
            // 중복 시 동일 PK만 대입한다. 기존 상태·identityVersion·감사 시각을 절대 초기화하지 않는다.
            onUpdate = { it[LearnersTable.userId] = userId },
        ) {
            it[LearnersTable.userId] = userId
            it[status] = "ACTIVE"
            it[identityVersion] = 0L
            it[createdAt] = nowUtc
            it[updatedAt] = nowUtc
        }
        // 기존/신규 행 모두 DB 행 잠금을 유지한다. JVM 내부 mutex에 의존하지 않는다.
        val row = LearnersTable.selectAll()
            .where { LearnersTable.userId eq userId }
            .forUpdate()
            .single()
        return Learner(
            userId = row[LearnersTable.userId],
            status = row[LearnersTable.status],
            identityVersion = row[LearnersTable.identityVersion],
            createdAt = row[LearnersTable.createdAt],
            updatedAt = row[LearnersTable.updatedAt],
        )
    }
}
