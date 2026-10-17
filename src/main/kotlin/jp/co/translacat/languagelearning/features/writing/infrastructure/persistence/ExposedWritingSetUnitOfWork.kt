package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.application.GrowthProjector
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthChange
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import jp.co.translacat.languagelearning.features.growth.infrastructure.persistence.repository.ExposedGrowthRepository
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.repository.ExposedUserSettingsRepository
import jp.co.translacat.languagelearning.features.writing.application.WritingSetTransaction
import jp.co.translacat.languagelearning.features.writing.application.WritingSetUnitOfWork
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.repository.*
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal class ExposedWritingSetUnitOfWork(
    private val transactions: JdbcTransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) : WritingSetUnitOfWork {
    companion object {
        private val requireCurrentTransaction = {
            check(TransactionManager.currentOrNull() != null) {
                "Writing 교차 기능 저장소 연결은 소유 트랜잭션 안에서만 사용할 수 있습니다."
            }
        }

        /** 신규 Writing 저장소도 같은 트랜잭션에서 기존 사용자 잠금과 성장 저장소를 조립한다. */
        internal fun lockCurrentOwner(userId: Long, now: LocalDateTime) {
            ExposedLearnerRepository(requireCurrentTransaction).ensureAndLock(userId, now).requireActive()
        }

        internal fun currentGrowthProfile(userId: Long): GrowthProfile? =
            ExposedGrowthRepository(requireCurrentTransaction,
                settings = ExposedUserSettingsRepository(requireCurrentTransaction)).profile(userId)

        /** 조회는 학습자를 만들거나 다시 활성화하지 않고, 기존 tombstone만 확인한다. */
        internal fun requireCurrentOwnerActive(userId: Long) {
            ExposedLearnerRepository(requireCurrentTransaction).find(userId)?.requireActive()
        }

        /** 교차 기능 구현은 UoW에서 조립하고 Writing 활동에는 도메인 계약만 전달한다. */
        internal fun currentGrowthRecords(): GrowthRepository =
            ExposedGrowthRepository(requireCurrentTransaction,
                settings = ExposedUserSettingsRepository(requireCurrentTransaction))

        internal fun markLearningPrepared(userId: Long, date: LocalDate, now: LocalDateTime) {
            GrowthProjector(ExposedGrowthRepository(requireCurrentTransaction,
                settings = ExposedUserSettingsRepository(requireCurrentTransaction)))
                .apply(userId, GrowthChange.LearningPrepared(date), now)
        }
    }

    override suspend fun <T> write(userId: Long, block: WritingSetTransaction.() -> T): T = transactions.write {
        scoped { guard ->
            ExposedLearnerRepository(guard).ensureAndLock(userId, now()).requireActive()
            block()
        }
    }

    override suspend fun <T> read(block: WritingSetTransaction.() -> T): T = transactions.read {
        scoped { _ -> block() }
    }

    private fun now() = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)

    private fun <T> scoped(block: WritingSetTransaction.((() -> Unit)) -> T): T {
        val owner = Thread.currentThread()
        val transaction = TransactionManager.current()
        var active = true
        val guard = {
            check(active && Thread.currentThread() === owner && TransactionManager.currentOrNull() === transaction) {
                "Writing Repository는 소유 트랜잭션 안에서만 사용할 수 있습니다."
            }
        }
        val settings = ExposedUserSettingsRepository(guard)
        val scope = object : WritingSetTransaction {
            override val sets = ExposedWritingSetRepository(guard, settings = settings)
            override val items = ExposedWritingItemRepository(guard)
            override val answers = ExposedWritingAnswerRepository(guard)
            override val evaluations = ExposedWritingEvaluationRepository(guard)
            override val fingerprints = ExposedWritingFingerprintRepository(guard)
            override val growth = ExposedGrowthRepository(guard, settings = settings)
            override val nowUtc: LocalDateTime get() = now()
        }
        try {
            return block(scope, guard)
        } finally {
            active = false
        }
    }
}
