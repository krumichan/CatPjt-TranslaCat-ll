package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.writing.application.*
import jp.co.translacat.languagelearning.features.writing.domain.model.*
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.LocalDate
import kotlin.test.*

class WritingSetStateIntegrationTestLanguage {
    private val date = LocalDate.parse("2026-10-04")

    @Test
    fun `현재 설정이 달라도 저장 언어로 한번 평가하고 중복 성장을 막는다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 실제 저장 과제는 ko→ja이고 worker의 현재 설정은 en→fr이다.
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4))
                val answer = prepare(work, 730, """{"originLanguage":"ko","learningLanguage":"ja"}""")
                val requests = mutableListOf<String>()
                val model = ModelExecutionPort { command ->
                    requests.add(command.messages.single().content)
                    ModelExecutionResult(result, 0, 0, "synthetic", "fixed")
                }
                val worker = WritingEvaluationWorker(WritingEvaluationState(work), WritingEvaluationExecution(model))

                // 실행: 저장·재조회 후의 정상 평가와 동일 작업 재개를 확인한다.
                assertTrue(worker.process(730, answer.id, "en", "fr", date))
                assertFalse(worker.process(730, answer.id, "en", "fr", date))

                // 검증: 실제 Provider 요청 언어와 본문이 일치하고 공식 성장은 한번만 반영된다.
                assertEquals(1, requests.size)
                assertTrue(requests.single().contains("Feedback learningText fields and recommendedAnswers MUST be written in ja."))
                val payload = Json.parseToJsonElement(requests.single().substringAfter("<evaluation-data>\n")
                    .substringBefore("\n</evaluation-data>")).jsonObject
                assertEquals(JsonPrimitive("ko"), payload["originLanguage"])
                assertEquals(JsonPrimitive("ja"), payload["learningLanguage"])
                work.read {
                    assertEquals(WritingEvaluationStatus.SUCCESS, answers.findById(730, answer.id)?.evaluationStatus)
                    assertEquals(1, growth.profile(730)?.evaluationCount)
                }
            }
        }
    }

    @Test
    fun `불명 언어 과제는 모델과 성장 호출 없이 답안을 실패 상태로 보존한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: legacy snapshot의 언어를 현재 설정으로 메울 근거는 없다.
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4))
                val answer = prepare(work, 731, "{}")
                var calls = 0
                val model = ModelExecutionPort {
                    calls++
                    ModelExecutionResult(result, 0, 0, "synthetic", "fixed")
                }
                val worker = WritingEvaluationWorker(WritingEvaluationState(work), WritingEvaluationExecution(model))

                // 실행: 첫 처리와 재기동 worker에 해당하는 두 번째 처리를 수행한다.
                assertFalse(worker.process(731, answer.id, "ko", "en", date))
                assertFalse(worker.process(731, answer.id, "ko", "en", date))

                // 검증: 실패는 성공이 아니며 원문 답안과 공식 profile을 보존한다.
                assertEquals(0, calls)
                work.read {
                    val stored = assertNotNull(answers.findById(731, answer.id))
                    assertEquals("合成の回答です。", stored.text)
                    assertEquals(WritingEvaluationStatus.FAILED, stored.evaluationStatus)
                    assertEquals(0, growth.profile(731)?.evaluationCount)
                }
            }
        }
        db.connect().use { connection ->
            connection.prepareStatement("SELECT failure_message FROM language_learning_writing_evaluation WHERE user_id=?").use { statement ->
                statement.setLong(1, 731)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals("WRITING_EVALUATION_LANGUAGE_UNKNOWN", rows.getString(1))
                    assertFalse(rows.next())
                }
            }
        }
    }

    private suspend fun prepare(work: ExposedWritingSetUnitOfWork, userId: Long, snapshot: String): WritingAnswer {
        val generation = WritingGenerationState(work)
        val set = generation.getOrCreate(NewWritingSet(userId, date, WritingType.FREE, "language-$userId", 1, snapshot))
        val claim = assertNotNull(generation.claimNext(userId, set.id))
        assertTrue(generation.publishItem(userId, set.id, claim,
            NewWritingItem(1, WritingDifficulty.NORMAL, "좋아하는 활동을 써 보세요.", emptyList(), listOf("MEANING"), "합성 검사"), "synthetic"))
        work.write(userId) {
            growth.saveProfile(GrowthProfile(userId, state = "ACTIVE", createdAt = nowUtc, updatedAt = nowUtc))
        }
        val item = work.read { items.list(userId, set.id).single() }
        return WritingAnswerState(work).submit(userId, item.id, "合成の回答です。", null, date, 7, true)
    }

    private val result = Json.parseToJsonElement("""
        {"scores":{"meaning":80,"grammar":70,"vocabulary":60,"naturalness":75,"expression":65},
         "strengths":[],"weaknesses":[],"corrections":[],"recommendedAnswers":["合成例一。","合成例二。"],
         "explanation":{"originText":"합성 설명","learningText":"合成の説明。"},
         "profileSignals":{"strengthTags":[],"weaknessTags":[],"grammarPatterns":[],"vocabularyPatterns":[],
         "naturalnessPatterns":[],"expressionPatterns":[],"meaningPatterns":[],"recommendedFocus":[]}}
    """.trimIndent())
}
