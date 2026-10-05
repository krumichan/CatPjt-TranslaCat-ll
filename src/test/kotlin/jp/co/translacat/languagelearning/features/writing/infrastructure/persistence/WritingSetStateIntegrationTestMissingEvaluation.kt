package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.writing.application.*
import jp.co.translacat.languagelearning.features.writing.domain.model.*
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.*

class WritingSetStateIntegrationTestMissingEvaluation {
    private val date = LocalDate.parse("2026-10-04")
    private val clock = Clock.fixed(Instant.parse("2026-10-04T01:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `세 유형의 삭제된 평가는 원문을 보존하는 조회로 반환하고 쓰기와 소유권은 엄격하게 유지한다`() =
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    // 준비: 이 테스트가 생성한 무작위 catalog에서만 평가 행을 삭제한다.
                    val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)
                    val service = WritingReadService(work, clock)
                    val fixtures = WritingType.entries.mapIndexed { index, type -> prepare(work, 810L + index, type) }
                    db.connect().use { connection ->
                        connection.prepareStatement("DELETE FROM language_learning_writing_evaluation WHERE answer_id=?").use { sql ->
                            for (fixture in fixtures) {
                                sql.setLong(1, fixture.answer.id)
                                assertEquals(1, sql.executeUpdate())
                            }
                        }
                    }
                    val before = snapshot(db)

                    // 실행: 반복 읽기와 날짜 조회를 모두 수행하며 다른 사용자에게는 원문을 내보내지 않는다.
                    for (fixture in fixtures) {
                        val response = assertNotNull(service.byId(fixture.answer.userId, fixture.set.id, date, 7))
                        assertEquals(response, service.byId(fixture.answer.userId, fixture.set.id, date, 7))
                        assertEquals(response, service.byDate(fixture.answer.userId, date, fixture.set.writingType, date, 7))
                        assertNull(service.byId(999, fixture.set.id, date, 7))
                        work.read { assertTrue(answers.history(999, fixture.item.id).isEmpty()) }
                        val item = response.getValue("items").jsonArray.single().jsonObject
                        val attempt = item.getValue("attempts").jsonArray.single().jsonObject

                        // 검증: 평가/점수/status는 null이며 오늘 원문과 answered 상태는 남는다.
                        assertEquals(JsonPrimitive("Synthetic stored answer"), attempt["answer"])
                        assertEquals(JsonNull, attempt["evaluationStatus"])
                        assertEquals(JsonNull, attempt["evaluation"])
                        assertEquals(JsonPrimitive("WRITING_EVALUATION_MISSING"), attempt["evaluationFailureMessage"])
                        assertEquals(JsonPrimitive(true), item["answered"])
                        assertEquals(JsonPrimitive(true), item["answeredToday"])
                        assertEquals(JsonPrimitive(false), item["canSubmit"])
                        assertEquals(fixture.set.status.name, response.getValue("status").jsonPrimitive.content)
                        assertEquals("WRITING_EVALUATION_MISSING", assertFailsWith<IllegalStateException> {
                            work.read { answers.findById(fixture.answer.userId, fixture.answer.id) }
                        }.message)
                        assertEquals("WRITING_EVALUATION_MISSING", assertFailsWith<IllegalStateException> {
                            WritingAnswerState(work).submit(fixture.answer.userId, fixture.item.id,
                                "Must not replace", null, date, 7, true)
                        }.message)
                    }
                    assertEquals(before, snapshot(db))
                }
            }
        }

    @Test
    fun `정상 성공 평가와 PENDING 잠금 및 FAILED 재시도 계약은 그대로 유지한다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 상태가 정상인 대조군을 실제 DB에 만들되 Provider는 호출하지 않는다.
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)
                val service = WritingReadService(work, clock)
                val pending = prepare(work, 820, WritingType.FREE)
                val success = prepare(work, 821, WritingType.FREE)
                val failed = prepare(work, 822, WritingType.FREE)
                db.connect().use { connection ->
                    writeSuccessfulEvaluation(connection, success.answer.id)
                    connection.prepareStatement("UPDATE language_learning_writing_evaluation SET status='FAILED', failure_message='SYNTHETIC_FAILURE' WHERE answer_id=?").use { sql ->
                        sql.setLong(1, failed.answer.id)
                        assertEquals(1, sql.executeUpdate())
                    }
                }
                val before = snapshot(db)

                // 실행·검증: 기존 공식 값은 그대로이며 정상 FAILED만 재제출 가능하다.
                for ((fixture, status, canSubmit) in listOf(
                    Triple(pending, "PENDING", false), Triple(success, "SUCCESS", false), Triple(failed, "FAILED", true),
                )) {
                    val item = assertNotNull(service.byId(fixture.answer.userId, fixture.set.id, date, 7))
                        .getValue("items").jsonArray.single().jsonObject
                    val attempt = item.getValue("attempts").jsonArray.single().jsonObject
                    assertEquals(JsonPrimitive(status), attempt["evaluationStatus"])
                    assertEquals(JsonPrimitive(canSubmit), item["canSubmit"])
                    if (status == "SUCCESS") {
                        assertEquals(JsonPrimitive(80), attempt.getValue("evaluation").jsonObject["overall"])
                        assertEquals(JsonPrimitive(90), attempt.getValue("evaluation").jsonObject["meaning"])
                        assertEquals(JsonNull, attempt["evaluationFailureMessage"])
                    } else assertEquals(JsonNull, attempt["evaluation"])
                }
                assertEquals(before, snapshot(db))

                // 실행·검증: 쓰기 경로에서 원래 허용되던 FAILED 재시도는 같은 답안의 PENDING으로 이어진다.
                val retried = WritingAnswerState(work).submit(822, failed.item.id, "Retried synthetic answer", null, date, 7, true)
                assertEquals(failed.answer.id, retried.id)
                assertEquals(WritingEvaluationStatus.PENDING, retried.evaluationStatus)
                assertEquals("Retried synthetic answer", retried.text)
            }
        }
    }

    @Test
    fun `평가 행이 존재하는 불완전 SUCCESS를 삭제 평가처럼 숨기지 않는다`() = LocalScratchMysql.use { db ->
        DatabaseFactory(db.settings()).use { factory ->
            runBlocking {
                // 준비: 무작위 테스트 catalog의 SUCCESS 행에 필수 점수가 없는 손상을 만든다.
                val work = ExposedWritingSetUnitOfWork(JdbcTransactionRunner(factory.database, 4), clock)
                val fixture = prepare(work, 823, WritingType.FREE)
                db.connect().use { connection ->
                    connection.prepareStatement("UPDATE language_learning_writing_evaluation SET status='SUCCESS' WHERE answer_id=?").use { sql ->
                        sql.setLong(1, fixture.answer.id)
                        assertEquals(1, sql.executeUpdate())
                    }
                }
                val before = snapshot(db)

                // 실행·검증: 행 부재만 nullable이고 기존 SUCCESS의 필수 값 검증은 계속 실패한다.
                assertFailsWith<IllegalStateException> {
                    WritingReadService(work, clock).byId(823, fixture.set.id, date, 7)
                }
                assertEquals(before, snapshot(db))
            }
        }
    }

    private data class Fixture(val set: WritingSet, val item: WritingItem, val answer: WritingAnswer)

    private suspend fun prepare(work: ExposedWritingSetUnitOfWork, userId: Long, type: WritingType): Fixture {
        val generation = WritingGenerationState(work)
        val set = generation.getOrCreate(NewWritingSet(userId, date, type, "missing-evaluation-$userId", 1, "{}"))
        val claim = assertNotNull(generation.claimNext(userId, set.id))
        val guide = if (type == WritingType.GUIDED) listOf("Synthetic guidance") else emptyList()
        assertTrue(generation.publishItem(userId, set.id, claim,
            NewWritingItem(1, WritingDifficulty.NORMAL, "Synthetic task", emptyList(), listOf("MEANING"),
                "Synthetic focus", guide, guide, guide), "synthetic"))
        val item = work.read { items.list(userId, set.id).single() }
        val answer = WritingAnswerState(work).submit(userId, item.id, "Synthetic stored answer", null, date, 7, true)
        return Fixture(assertNotNull(generation.find(userId, set.id)), item, answer)
    }

    private fun writeSuccessfulEvaluation(connection: Connection, answerId: Long) {
        connection.prepareStatement("""
            UPDATE language_learning_writing_evaluation
            SET status='SUCCESS', overall_score=80, meaning_score=90, grammar_score=80,
                vocabulary_score=70, naturalness_score=60, expression_score=50,
                strengths_json='[]', weaknesses_json='[]', corrections_json='[]',
                recommended_answers_json='["Synthetic example"]', explanation_json='{"originText":"Synthetic","learningText":"Synthetic"}',
                evaluation_rubric_version='existing-rubric', scoring_policy_version='existing-policy',
                prompt_version='existing-prompt', evaluated_at='2026-10-04 01:00:00'
            WHERE answer_id=?
        """.trimIndent()).use { sql ->
            sql.setLong(1, answerId)
            assertEquals(1, sql.executeUpdate())
        }
    }

    private fun snapshot(db: LocalScratchMysql): Map<String, List<String>> = db.connect().use { connection ->
        // 이 함수는 이 테스트가 만든 catalog의 저장 행 전체를 읽어 조회 전후 부작용을 비교한다.
        val tables = connection.createStatement().use { sql ->
            sql.executeQuery("SHOW TABLES").use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        tables.sorted().associateWith { table ->
            require(Regex("[a-zA-Z0-9_]+").matches(table))
            connection.createStatement().use { sql ->
                sql.executeQuery("SELECT * FROM `$table`").use { rows ->
                    buildList {
                        while (rows.next()) add((1..rows.metaData.columnCount).joinToString("|") { column ->
                            rows.getBytes(column)?.let { Base64.getEncoder().encodeToString(it) } ?: "<NULL>"
                        })
                    }.sorted()
                }
            }
        }
    }
}
