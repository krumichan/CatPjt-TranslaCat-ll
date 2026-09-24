package jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence

import jp.co.translacat.languagelearning.features.keyword.application.KeywordTransaction
import jp.co.translacat.languagelearning.features.keyword.application.KeywordUnitOfWork
import jp.co.translacat.languagelearning.features.keyword.domain.policy.KeywordPolicy
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.repository.ExposedCustomKeywordRepository
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.repository.ExposedSystemKeywordLocaleRepository
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.repository.ExposedSystemKeywordRepository
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.repository.ExposedSystemKeywordSelectionRepository
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.table.KeywordCatalogLockTable
import jp.co.translacat.languagelearning.features.learner.infrastructure.persistence.repository.ExposedLearnerRepository
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.SQLException
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal class ExposedKeywordUnitOfWork(
    private val transactions: JdbcTransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
) : KeywordUnitOfWork {
    override suspend fun <T> execute(learnerId: Long?, block: KeywordTransaction.() -> T): T {
        try {
            return transactions.write {
                val transaction = TransactionManager.current()
                val owner = Thread.currentThread()
                var active = true
                val guard = {
                    check(active && Thread.currentThread() === owner && TransactionManager.currentOrNull() === transaction) {
                        "Keyword Repository는 생성된 트랜잭션 안에서만 사용할 수 있습니다."
                    }
                }

                fun now() = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
                // 모든 키워드 경로가 catalog → learner 순서로 잠근다. 외부 HTTP/AI는 이 블록에 없다.
                check(
                    KeywordCatalogLockTable.selectAll()
                    .where { KeywordCatalogLockTable.id eq 1 }
                    .forUpdate()
                    .singleOrNull() != null) {
                    "키워드 잠금 기준 행이 없습니다. V005 migration을 확인해 주세요."
                }
                if (learnerId != null) ExposedLearnerRepository(guard).ensureAndLock(learnerId, now()).requireActive()
                val scope = object : KeywordTransaction {
                    override val system = ExposedSystemKeywordRepository(guard)
                    override val custom = ExposedCustomKeywordRepository(guard)
                    override val selections = ExposedSystemKeywordSelectionRepository(guard)
                    override val locales = ExposedSystemKeywordLocaleRepository(guard)
                    override val nowUtc: LocalDateTime get() = now()
                }
                try {
                    block(scope)
                } finally {
                    active = false
                }
            }
        } catch (failure: SQLException) {
            // MySQL unique 위반만 업무 중복 오류로 바꾼다. FK/연결 오류를 성공이나 기본값으로 숨기지 않는다.
            if (generateSequence(failure as Throwable?) { it.cause }.filterIsInstance<SQLException>()
                    .any { it.errorCode == 1062 }
            ) {
                throw KeywordPolicy.duplicated()
            }
            throw failure
        }
    }
}
