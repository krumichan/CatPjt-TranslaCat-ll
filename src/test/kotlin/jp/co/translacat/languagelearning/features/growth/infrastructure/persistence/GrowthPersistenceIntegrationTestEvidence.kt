package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.domain.model.LearningEvidenceFilter
import jp.co.translacat.languagelearning.features.overview.application.LearningEvidenceQuery
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.sql.Connection
import java.time.LocalDate
import kotlin.system.measureNanoTime
import kotlin.test.*

/** 테스트가 생성한 로컬 MySQL catalog에서만 synthetic 원본을 만들고 삭제한다. */
class GrowthPersistenceIntegrationTestEvidence {
    private val day = LocalDate.parse("2026-10-03")
    private fun work(factory: DatabaseFactory) = ExposedGrowthUnitOfWork(JdbcTransactionRunner(factory.database, 2))
    private fun Connection.sql(value: String) = createStatement().use { it.executeUpdate(value) }
    private fun Connection.owner(id: Long) = sql(
        "INSERT INTO language_learning_learner VALUES($id,'ACTIVE',1,'2026-10-03 00:00:00','2026-10-03 00:00:00')",
    )
    private fun Connection.speaking(id: Long, user: Long = 1, language: String = "en", kind: String = "SESSION_COACHING", date: String = "2026-10-03") {
        val snapshot = buildJsonObject {
            put("learningLanguage", language); put("originLanguage", "ko"); put("topicTitle", "Synthetic evidence $id")
            put("resultKind", kind)
            put("resultPolicyVersion", if (kind == "SESSION_COACHING") "free-session-coaching-v1" else "speaking-legacy-score-v1")
        }
        prepareStatement(
            "INSERT INTO language_learning_speaking_session " +
                "(id,user_id,create_idempotency_key,learning_date,snapshot_json,status,evaluation_status,completed_turns,total_duration_seconds,opening_json,usage_json,started_at,last_activity_at) " +
                "VALUES(?,?,?, ?,?,'COMPLETED','NOT_REQUESTED',5,100,'{}','{}','2026-10-03 00:00:00','2026-10-03 00:00:00')",
        ).use {
            it.setLong(1, id); it.setLong(2, user); it.setString(3, "synthetic-$id")
            it.setString(4, date); it.setString(5, snapshot.toString()); it.executeUpdate()
        }
    }

    @Test
    fun `실제 원본은 owner 언어 정책 날짜로 분리되고 재시도와 재연결은 활동을 늘리지 않는다`() {
        LocalScratchMysql.use { db ->
            // 준비: 현재 schema를 적용한 작업 소유 DB와 합성 세션만 사용한다.
            DatabaseFactory(db.settings()).use { factory ->
                db.connect().use { connection ->
                    connection.owner(1); connection.owner(2)
                    connection.speaking(1); connection.speaking(2, language = "ja")
                    connection.speaking(3, kind = "SCORED_EVALUATION"); connection.speaking(4, user = 2)
                    connection.speaking(5, date = "2026-10-02"); connection.speaking(6, date = "2026-10-04")
                    connection.sql("INSERT INTO language_learning_speaking_evaluation_job (session_id,problem_index,result_kind,result_policy_version,request_json,status,available_at,manual_retry_count,recovery_count) VALUES(1,0,'SESSION_COACHING','free-session-coaching-v1','{}','COMPLETED','2026-10-03',2,3)")
                }
                runBlocking {
                    val query = LearningEvidenceQuery(work(factory))

                    // 실행 및 검증: 경계 날짜와 결과 의미를 분리하며 조회는 점수 필드를 만들지 않는다.
                    val page = query.execute(1, "SPEAKING", "en", day, day, "SESSION_COACHING", "free-session-coaching-v1", null, 25)
                    val item = page.getValue("items").jsonArray.single().jsonObject
                    assertEquals("SPEAKING:-1", item.getValue("activityId").jsonPrimitive.content)
                    assertEquals("COMPLETED", item.getValue("resultStatus").jsonPrimitive.content)
                    assertFalse("overallScore" in item)
                    assertEquals(page, query.execute(1, "SPEAKING", "en", day, day, "SESSION_COACHING", "free-session-coaching-v1", null, 25))
                    assertEquals(1, query.execute(2, null, null, day, day, null, null, null, 25).getValue("items").jsonArray.size)
                    assertEquals(1, query.execute(1, null, null, day, day, "SCORED_EVALUATION", null, null, 25).getValue("items").jsonArray.size)
                    assertTrue(query.execute(1, null, null, day, day, null, "unknown-policy", null, 25).getValue("items").jsonArray.isEmpty())
                }
            }

            // 검증: 연결을 다시 열어도 저장된 사실만 반환하며 job의 재시도 횟수와 무관하다.
            DatabaseFactory(db.settings()).use { factory ->
                runBlocking {
                    val query = LearningEvidenceQuery(work(factory))
                    assertEquals(3, query.execute(1, null, null, day, day, null, null, null, 25).getValue("items").jsonArray.size)
                    db.connect().use {
                        it.sql("DELETE FROM language_learning_speaking_evaluation_job WHERE session_id=1")
                        it.sql("DELETE FROM language_learning_speaking_session WHERE id=1")
                    }
                    assertEquals(2, query.execute(1, null, null, day, day, null, null, null, 25).getValue("items").jsonArray.size)
                }
            }
        }
    }

    @Test
    fun `여섯 출처의 원래 상세 식별자와 날짜를 보존하고 metadata 누락은 제한으로 표시한다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                // 준비: Listening의 set과 session ID를 다르게 하여 상세 연결 오류를 드러낸다.
                db.connect().use { connection ->
                    connection.owner(1); connection.speaking(1)
                    connection.sql("INSERT INTO language_learning_daily_set(id,user_id,learning_date,writing_type,snapshot_id,sentence_count,status,snapshot_json,created_at,updated_at) VALUES(1,1,'2026-10-03','FREE','synthetic',5,'READY','{}','2026-10-03','2026-10-03')")
                    connection.sql("INSERT INTO language_learning_practice_set(id,user_id,learning_date,domain,mode,complexity_band,question_count,request_json,status,generation_status,started_at) VALUES(1,1,'2026-10-03','READING','READING',3,5,'{}','COMPLETED','COMPLETED','2026-10-03'),(2,1,'2026-10-03','VOCABULARY','LEGACY',3,5,'{}','COMPLETED','COMPLETED','2026-10-03')")
                    connection.sql("INSERT INTO language_learning_listening_set VALUES(10,1,'2026-10-03','en','DICTATION','COMPLETED',1,'{\"originLanguage\":\"ko\"}','2026-10-03','2026-10-03')")
                    connection.sql("INSERT INTO language_learning_listening_session VALUES(20,1,10,'synthetic','COMPLETED',1,'{}','2026-10-03','2026-10-03')")
                    connection.sql("INSERT INTO language_learning_level_test_session(id,session_uid,user_id,session_type,status,origin_language,learning_language,timezone,current_question_number,current_complexity_band,domain_scores_json,started_at,last_activity_at,completed_at,completed_date,idempotency_key) VALUES(30,'synthetic',1,'INITIAL','COMPLETED','ko','en','Asia/Seoul',20,3,'{}','2026-10-02','2026-10-02','2026-10-02','2026-10-03','synthetic')")
                }

                // 실행
                val page = runBlocking { LearningEvidenceQuery(work(factory)).execute(1, null, null, day, day, null, null, null, 25) }
                val rows = page.getValue("items").jsonArray.map { it.jsonObject }

                // 검증: 미상 정책을 prompt version이나 오늘 설정으로 채우지 않는다.
                assertEquals(setOf("WRITING:-1", "SPEAKING:-1", "READING:-1", "VOCABULARY:-2", "LISTENING:-20", "LEVEL_TEST:30"), rows.map { it.getValue("activityId").jsonPrimitive.content }.toSet())
                val writing = rows.single { it.getValue("source").jsonPrimitive.content == "WRITING" }
                assertEquals(JsonNull, writing["learningLanguage"])
                assertEquals(JsonNull, writing["policyVersion"])
                assertEquals("LIMITED", writing.getValue("availability").jsonPrimitive.content)
                assertTrue(rows.all { it.getValue("learningDate").jsonPrimitive.content == "2026-10-03" })
            }
        }
    }

    @Test
    fun `Writing 문항별 평가를 세트 한 행으로 읽고 다른 정책을 하나로 추정하지 않는다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                // 준비: 같은 세트의 두 문항과 서로 다른 점수를 저장한다.
                db.connect().use { connection ->
                    connection.owner(1)
                    connection.sql("INSERT INTO language_learning_daily_set(id,user_id,learning_date,writing_type,snapshot_id,sentence_count,status,snapshot_json,created_at,updated_at) VALUES(1,1,'2026-10-03','GUIDED','synthetic-policy',2,'COMPLETED','{\"originLanguage\":\"ko\",\"learningLanguage\":\"en\"}','2026-10-03','2026-10-03')")
                    for (id in 1..2) {
                        connection.sql("INSERT INTO language_learning_daily_item(id,daily_set_id,user_id,item_order,difficulty,origin_text,keywords_json,focus_metrics_json,focus_reason,created_at,updated_at) VALUES($id,1,1,$id,'NORMAL','Synthetic prompt','[]','[]','fixture','2026-10-03','2026-10-03')")
                        connection.sql("INSERT INTO language_learning_writing_answer(id,daily_item_id,user_id,attempt_date,answer_text,submitted_at,created_at,updated_at) VALUES($id,$id,1,'2026-10-03','Synthetic answer','2026-10-03','2026-10-03','2026-10-03')")
                        connection.sql("INSERT INTO language_learning_writing_evaluation(answer_id,user_id,evaluation_context,status,overall_score,scoring_policy_version,created_at,updated_at) VALUES($id,1,'DAILY','SUCCESS',${id * 40},'saved-policy-v1','2026-10-03','2026-10-03')")
                    }
                }
                runBlocking {
                    val query = LearningEvidenceQuery(work(factory))

                    // 실행 및 검증: 점수를 평균 내지 않고 저장된 일치 정책만 조회 필터에 사용한다.
                    val first = query.execute(1, "WRITING", "en", day, day, "SCORED_EVALUATION", "saved-policy-v1", null, 25)
                    val row = first.getValue("items").jsonArray.single().jsonObject
                    assertEquals("saved-policy-v1", row.getValue("policyVersion").jsonPrimitive.content)
                    assertFalse("overallScore" in row)
                    db.connect().use { it.sql("UPDATE language_learning_writing_evaluation SET scoring_policy_version='saved-policy-v2' WHERE answer_id=2") }
                    val mixed = query.execute(1, "WRITING", "en", day, day, null, null, null, 25).getValue("items").jsonArray.single().jsonObject
                    assertEquals(JsonNull, mixed["policyVersion"])
                    assertEquals("LIMITED", mixed.getValue("availability").jsonPrimitive.content)
                    assertTrue(query.execute(1, "WRITING", "en", day, day, null, "saved-policy-v1", null, 25).getValue("items").jsonArray.isEmpty())
                }
            }
        }
    }

    @Test
    fun `다수 이력도 한 metadata 쿼리와 제한된 페이지로 읽고 삭제 후 cursor에 누락이 없다`() {
        LocalScratchMysql.use { db ->
            DatabaseFactory(db.settings()).use { factory ->
                // 준비: 원문 없이 500개의 합성 세션을 저장한다.
                db.connect().use { connection ->
                    connection.owner(1)
                    connection.autoCommit = false
                    for (id in 1L..500L) connection.speaking(id)
                    connection.commit()
                }
                runBlocking {
                    val unit = work(factory)
                    val query = LearningEvidenceQuery(unit)
                    var first: JsonObject? = null
                    val elapsed = measureNanoTime { first = query.execute(1, null, null, day, day, null, null, null, 25) }

                    // 실행: 첫 페이지 내 행 삭제 후 다음 페이지를 읽는다.
                    db.connect().use { it.sql("DELETE FROM language_learning_speaking_session WHERE id=500") }
                    val second = query.execute(1, null, null, day, day, null, null, first!!.getValue("nextCursor").jsonPrimitive.content, 25)

                    // 검증: 원문 적재 없이 25행만 반환하고 query 수는 이력 크기에 비례하지 않는다.
                    assertEquals(25, first!!.getValue("items").jsonArray.size)
                    assertEquals("SPEAKING:-475", second.getValue("items").jsonArray.first().jsonObject.getValue("activityId").jsonPrimitive.content)
                    unit.read {
                        val tx = org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.current()
                        val before = tx.statementCount
                        assertEquals(26, records.learningEvidence(LearningEvidenceFilter(1, null, null, day, day, null, null, null, 26)).size)
                        assertEquals(1, tx.statementCount - before)
                    }
                    println("EVIDENCE_SYNTHETIC_PERFORMANCE rows=500 page=25 queryCount=1 responseBytes=${first.toString().toByteArray().size} elapsedMs=${elapsed / 1_000_000.0}")
                }
            }
        }
    }
}
