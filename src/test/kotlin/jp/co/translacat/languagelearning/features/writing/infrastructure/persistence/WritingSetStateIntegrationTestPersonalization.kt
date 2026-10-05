package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.writing.application.WritingAnswerState
import jp.co.translacat.languagelearning.features.writing.application.WritingGenerationState
import jp.co.translacat.languagelearning.features.writing.domain.model.*
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingScoring
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import java.time.*
import kotlin.test.*

class WritingSetStateIntegrationTestPersonalization {
    private val clock = Clock.fixed(Instant.parse("2026-10-03T01:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `최근 문맥 점수는 원본 언어 유형 owner 정책과 삭제를 따르고 최대 20개만 읽는다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 실 DB에 서로 다른 owner·언어·유형·정책과 metadata 누락을 가진 합성 기록을 저장한다.
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)
                suspend fun seed(
                    day: Int, language: String = "en", type: WritingType = WritingType.FREE,
                    owner: Long = 121, policy: String? = WritingScoring.policyVersion,
                    rubric: String? = WritingScoring.rubricVersion, status: String = "SUCCESS",
                    snapshotOverride: String? = null,
                ): Long {
                    val date = LocalDate.of(2026, 8, 1).plusDays(day.toLong())
                    val generation = WritingGenerationState(work)
                    val set = generation.getOrCreate(NewWritingSet(
                        owner, date, type, "synthetic-$owner-$day-${type.name}", 1,
                        "{\"originLanguage\":\"ko\",\"learningLanguage\":\"$language\"}",
                    ))
                    val claim = assertNotNull(generation.claimNext(owner, set.id))
                    val guidance = if (type == WritingType.GUIDED) listOf("합성 안내") else emptyList()
                    assertTrue(generation.publishItem(owner, set.id, claim, NewWritingItem(
                        1, WritingDifficulty.NORMAL, "합성 문항 $day", emptyList(), emptyList(), "합성 초점",
                        guidance, guidance, guidance,
                    ), "existing-prompt"))
                    val item = work.read { items.list(owner, set.id).single() }
                    val answer = WritingAnswerState(work).submit(owner, item.id, "Synthetic answer", null, date, 7, true)
                    db.connect().use { connection ->
                        connection.prepareStatement(
                            "UPDATE language_learning_writing_evaluation SET status=?, overall_score=?, " +
                                "scoring_policy_version=?, evaluation_rubric_version=?, evaluated_at='2026-10-03 01:00:00' " +
                                "WHERE answer_id=? AND user_id=?",
                        ).use { statement ->
                            statement.setString(1, status)
                            statement.setInt(2, day)
                            statement.setString(3, policy)
                            statement.setString(4, rubric)
                            statement.setLong(5, answer.id)
                            statement.setLong(6, owner)
                            assertEquals(1, statement.executeUpdate())
                        }
                        if (snapshotOverride != null) connection.prepareStatement(
                            "UPDATE language_learning_daily_set SET snapshot_json=? WHERE id=? AND user_id=?",
                        ).use { statement ->
                            statement.setString(1, snapshotOverride)
                            statement.setLong(2, set.id)
                            statement.setLong(3, owner)
                            assertEquals(1, statement.executeUpdate())
                        }
                    }
                    return answer.id
                }
                for (day in 1..21) seed(day)
                seed(22, language = "ja")
                seed(23, type = WritingType.TRANSLATION)
                seed(24, type = WritingType.GUIDED)
                seed(25, owner = 122)
                seed(26, policy = "legacy-policy")
                seed(27, rubric = "legacy-rubric")
                seed(28, policy = null)
                seed(29, status = "FAILED")
                seed(30, snapshotOverride = "{}")
                seed(31, snapshotOverride = "legacy-unknown-snapshot")
                val deleted = seed(32)

                // 실행: 이 테스트가 만든 결과 하나를 삭제하여 존재하지 않는 근거가 사라지는지 확인한다.
                db.connect().use { connection ->
                    connection.prepareStatement(
                        "DELETE FROM language_learning_writing_evaluation WHERE answer_id=? AND user_id=121",
                    ).use { statement ->
                        statement.setLong(1, deleted)
                        assertEquals(1, statement.executeUpdate())
                    }
                }
                val scores = work.read { evaluations.recentDailyScores(121, "en", WritingType.FREE) }

                // 검증: 날짜 동률에도 안정적으로 최신 20개를 골라 다른 문맥의 점수를 섞지 않는다.
                assertEquals((21 downTo 2).toList(), scores.map { it.overall })
                assertTrue(scores.all { it.meaning == null && it.grammar == null })
                assertEquals(listOf(22), work.read {
                    evaluations.recentDailyScores(121, "ja", WritingType.FREE).map { it.overall }
                })
                assertEquals(listOf(23), work.read {
                    evaluations.recentDailyScores(121, "en", WritingType.TRANSLATION).map { it.overall }
                })
                assertEquals(listOf(24), work.read {
                    evaluations.recentDailyScores(121, "en", WritingType.GUIDED).map { it.overall }
                })
                assertEquals(listOf(25), work.read {
                    evaluations.recentDailyScores(122, "en", WritingType.FREE).map { it.overall }
                })
                assertTrue(work.read { evaluations.recentDailyScores(123, "en", WritingType.FREE) }.isEmpty())
            }
        }
    }
}
