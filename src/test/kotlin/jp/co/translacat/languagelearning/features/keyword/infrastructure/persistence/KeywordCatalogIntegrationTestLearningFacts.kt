package jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence

import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeywordCatalogIntegrationTestLearningFacts {
    @Test
    fun `LL 사용자별 Writing 시작과 공개 Speaking만 키워드 예약의 기준이 된다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 합성 사용자 둘만 만들고 아직 공개되지 않은 opening intent를 저장한다.
                val facts = ExposedKeywordLearningFacts(JdbcTransactionRunner(factory.database, 2))
                sql(
                    db,
                    """INSERT INTO language_learning_learner(user_id, created_at, updated_at)
                VALUES (731, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)), (732, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                )
                sql(
                    db,
                    """INSERT INTO language_learning_speaking_session
                (user_id, create_idempotency_key, learning_date, snapshot_json, status, evaluation_status,
                 completed_turns, total_duration_seconds, opening_json, usage_json, started_at, last_activity_at)
                VALUES (731, 'synthetic-opening', CURRENT_DATE, '{}', 'IN_PROGRESS', 'NOT_REQUESTED',
                 0, 0, '{"_executionState":"RUNNING"}', '{}', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                )

                // 실행·검증: 실패 intent는 시작으로 남지 않고, 공개가 확정된 소유자만 시작으로 판정한다.
                assertFalse(facts.hasStartedLearning(731))
                sql(
                    db,
                    """UPDATE language_learning_speaking_session SET opening_json='{"_executionState":"FAILED"}'
                WHERE user_id=731""",
                )
                assertFalse(facts.hasStartedLearning(731))
                sql(
                    db,
                    """UPDATE language_learning_speaking_session SET opening_json='{"_executionState":"READY"}'
                WHERE user_id=731""",
                )
                assertTrue(facts.hasStartedLearning(731))
                assertFalse(facts.hasStartedLearning(732))

                // 실행·검증: Writing은 원본과 같이 문항 완성 전 세트의 존재부터 시작으로 판정한다.
                sql(
                    db,
                    """INSERT INTO language_learning_daily_set
                (user_id, learning_date, writing_type, snapshot_id, sentence_count, status, snapshot_json, created_at, updated_at)
                VALUES (732, CURRENT_DATE, 'FREE', 'synthetic-keyword-writing', 1, 'GENERATING', '{}', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                )
                assertTrue(facts.hasStartedLearning(732))
                assertFalse(facts.hasStartedLearning(733))
            }
        }
    }

    private fun sql(db: LocalScratchMysql, statement: String) {
        db.connect().use { connection -> connection.createStatement().use { it.executeUpdate(statement) } }
    }
}
