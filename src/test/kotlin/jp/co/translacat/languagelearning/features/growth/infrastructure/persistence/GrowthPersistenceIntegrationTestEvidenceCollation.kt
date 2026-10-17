package jp.co.translacat.languagelearning.features.growth.infrastructure.persistence

import jp.co.translacat.languagelearning.features.overview.application.LearningEvidenceQuery
import jp.co.translacat.languagelearning.shared.persistence.DatabaseFactory
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import jp.co.translacat.languagelearning.support.LocalScratchMysql
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.sql.Connection
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 기존 데이터를 변환하지 않고 운영과 같은 혼합 collation을 작업 소유 로컬 catalog에서 재현한다. */
class GrowthPersistenceIntegrationTestEvidenceCollation {
    private val day = LocalDate.parse("2026-10-03")
    private val savedPolicy = "saved-policy-v1・저장정책・検証😀"
    private val savedTitle = "고객 회의・要件確認😀"

    @Test
    fun `혼합 collation의 여섯 출처는 원본 문자열과 NULL을 보존하며 필터와 소유권을 적용한다`() = withMixedCatalog { query ->
        // 실행: 서로 다른 collation의 저장 원본을 같은 UNION으로 읽는다.
        val all = query.rows()
        val byId = all.associateBy { it.getValue("activityId").jsonPrimitive.content }

        // 검증: 한국어·일본어·보조 평면 문자와 누락 metadata를 추정 없이 보존한다.
        assertEquals(8, all.size)
        assertEquals(savedTitle, byId.getValue("SPEAKING:-1").getValue("title").jsonPrimitive.content)
        assertEquals(savedPolicy, byId.getValue("WRITING:-1").getValue("policyVersion").jsonPrimitive.content)
        assertEquals("Daily Writing · GUIDED", byId.getValue("WRITING:-1").getValue("title").jsonPrimitive.content)
        assertEquals("COMPLETED", byId.getValue("WRITING:-1").getValue("status").jsonPrimitive.content)
        assertEquals("SUCCESS", byId.getValue("WRITING:-1").getValue("resultStatus").jsonPrimitive.content)
        val unknown = byId.getValue("WRITING:-2")
        assertEquals(JsonNull, unknown["learningLanguage"])
        assertEquals(JsonNull, unknown["originLanguage"])
        assertEquals(JsonNull, unknown["policyVersion"])
        assertEquals("NOT_REQUESTED", unknown.getValue("resultStatus").jsonPrimitive.content)
        assertEquals(JsonNull, byId.getValue("LISTENING:-20")["resultStatus"])
        assertEquals(JsonNull, byId.getValue("LEVEL_TEST:30")["policyVersion"])

        // 실행 및 검증: 단일 출처도 같은 문자열 변환을 거치며 조회 조건과 owner 경계를 유지한다.
        for (source in listOf("WRITING", "SPEAKING", "LISTENING", "READING", "VOCABULARY", "LEVEL_TEST")) {
            assertEquals(all.filter { it.getValue("source").jsonPrimitive.content == source }, query.rows(source = source))
        }
        assertEquals(
            setOf("WRITING:-1", "SPEAKING:-1", "SPEAKING:-2", "READING:-1", "LISTENING:-20"),
            query.rows(language = "ja").ids().toSet(),
        )
        assertEquals(listOf("SPEAKING:-1"), query.rows(kind = "SESSION_COACHING").ids())
        assertEquals(setOf("SPEAKING:-1", "WRITING:-1"), query.rows(policy = savedPolicy).ids().toSet())
        assertTrue(query.rows(language = "JA").isEmpty())
        assertTrue(query.rows(policy = savedPolicy.uppercase()).isEmpty())
        assertTrue(query.rows(policy = "unknown-policy").isEmpty())
        assertEquals(listOf("SPEAKING:-99"), query.rows(userId = 2).ids())
        assertTrue(query.rows(userId = 3).isEmpty())
    }

    @Test
    fun `혼합 collation의 동일 날짜 keyset은 출처와 ID 경계에서 중복 누락 없이 다음 페이지를 읽는다`() = withMixedCatalog { query ->
        // 실행: 출처 전환과 같은 출처의 ID 경계를 모두 지나도록 두 행씩 읽는다.
        val pages = mutableListOf<List<JsonObject>>()
        var cursor: String? = null
        do {
            val page = query.execute(1, null, null, day, day, null, null, cursor, 2)
            pages += page.getValue("items").jsonArray.map { it.jsonObject }
            cursor = page.getValue("nextCursor").jsonPrimitive.contentOrNull
            check(pages.size <= 5) { "동일 cursor를 반복하지 않아야 합니다." }
        } while (cursor != null)

        // 검증: 모든 행의 날짜를 유지하고 source ASC / id DESC 순서로 한 번씩만 반환한다.
        val rows = pages.flatten()
        assertEquals(listOf(2, 2, 2, 2), pages.map { it.size })
        assertEquals(
            listOf("LEVEL_TEST:30", "LISTENING:-20", "READING:-1", "SPEAKING:-2", "SPEAKING:-1", "VOCABULARY:-2", "WRITING:-2", "WRITING:-1"),
            rows.ids(),
        )
        assertEquals(8, rows.ids().distinct().size)
        assertTrue(rows.all { it.getValue("learningDate").jsonPrimitive.content == day.toString() })
    }

    private fun withMixedCatalog(action: suspend (LearningEvidenceQuery) -> Unit) = LocalScratchMysql.use { db ->
        // 준비: 생성에 성공한 임시 catalog만 변경한다. 명시 collation이 없는 migration은 0900을 상속한다.
        db.connect().use { connection ->
            connection.sql("ALTER DATABASE `${db.name}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
        }
        DatabaseFactory(db.settings()).use { factory ->
            db.connect().use { connection ->
                for (table in listOf("daily_set", "level_test_session")) {
                    assertEquals("utf8mb4_unicode_ci", connection.statusCollation(db.name, table))
                }
                for (table in listOf("speaking_session", "listening_session", "listening_set", "practice_set")) {
                    assertEquals("utf8mb4_0900_ai_ci", connection.statusCollation(db.name, table))
                }
                connection.seedEvidence()
            }
            val work = ExposedGrowthUnitOfWork(JdbcTransactionRunner(factory.database, 2))
            runBlocking { action(LearningEvidenceQuery(work)) }
        }
    }

    private suspend fun LearningEvidenceQuery.rows(
        userId: Long = 1, source: String? = null, language: String? = null,
        kind: String? = null, policy: String? = null,
    ) = execute(userId, source, language, day, day, kind, policy, null, 25)
        .getValue("items").jsonArray.map { it.jsonObject }

    private fun List<JsonObject>.ids() = map { it.getValue("activityId").jsonPrimitive.content }

    private fun Connection.statusCollation(catalog: String, table: String): String = prepareStatement(
        "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME='status'",
    ).use { statement ->
        statement.setString(1, catalog)
        statement.setString(2, "language_learning_$table")
        statement.executeQuery().use { result ->
            check(result.next())
            result.getString(1)
        }
    }

    private fun Connection.sql(statement: String, vararg values: Any) = prepareStatement(statement).use { query ->
        values.forEachIndexed { index, value -> query.setObject(index + 1, value) }
        query.executeUpdate()
    }

    private fun Connection.seedEvidence() {
        // 준비: 실제 학습 내용 대신 문자열 경계와 소유권 확인에 필요한 합성 원본만 저장한다.
        for (owner in 1..2) {
            sql("INSERT INTO language_learning_learner VALUES(?,'ACTIVE',1,'2026-10-03','2026-10-03')", owner)
        }
        val languages = buildJsonObject { put("learningLanguage", "ja"); put("originLanguage", "ko") }.toString()
        sql(
            "INSERT INTO language_learning_daily_set(id,user_id,learning_date,writing_type,snapshot_id,sentence_count,status,snapshot_json,created_at,updated_at) " +
                "VALUES(1,1,'2026-10-03','GUIDED','synthetic-policy',1,'COMPLETED',?,'2026-10-03','2026-10-03')," +
                "(2,1,'2026-10-03','FREE','synthetic-unknown',1,'READY','{}','2026-10-03','2026-10-03')",
            languages,
        )
        sql("INSERT INTO language_learning_daily_item(id,daily_set_id,user_id,item_order,difficulty,origin_text,keywords_json,focus_metrics_json,focus_reason,created_at,updated_at) VALUES(1,1,1,1,'NORMAL','합성 원문','[]','[]','fixture','2026-10-03','2026-10-03')")
        sql("INSERT INTO language_learning_writing_answer(id,daily_item_id,user_id,attempt_date,answer_text,submitted_at,created_at,updated_at) VALUES(1,1,1,'2026-10-03','合成回答','2026-10-03','2026-10-03','2026-10-03')")
        sql("INSERT INTO language_learning_writing_evaluation(answer_id,user_id,evaluation_context,status,overall_score,scoring_policy_version,created_at,updated_at) VALUES(1,1,'DAILY','SUCCESS',80,?,'2026-10-03','2026-10-03')", savedPolicy)
        for ((id, owner) in listOf(1 to 1, 2 to 1, 99 to 2)) {
            val snapshot = buildJsonObject {
                put("learningLanguage", "ja")
                put("originLanguage", "ko")
                put("topicTitle", savedTitle)
                put("resultKind", if (id == 1) "SESSION_COACHING" else "SCORED_EVALUATION")
                put("resultPolicyVersion", if (id == 1) savedPolicy else "speaking-legacy-score-v1")
            }
            sql(
                "INSERT INTO language_learning_speaking_session " +
                    "(id,user_id,create_idempotency_key,learning_date,snapshot_json,status,evaluation_status,completed_turns,total_duration_seconds,opening_json,usage_json,started_at,last_activity_at) " +
                    "VALUES(?,?,?,'2026-10-03',?,'COMPLETED','NOT_REQUESTED',5,100,'{}','{}','2026-10-03','2026-10-03')",
                id, owner, "synthetic-$id", snapshot.toString(),
            )
        }
        sql(
            "INSERT INTO language_learning_practice_set(id,user_id,learning_date,domain,mode,complexity_band,question_count,request_json,status,generation_status,started_at) " +
                "VALUES(1,1,'2026-10-03','READING','READING',3,1,?,'COMPLETED','COMPLETED','2026-10-03')," +
                "(2,1,'2026-10-03','VOCABULARY','LEGACY',3,1,'{}','COMPLETED','COMPLETED','2026-10-03')",
            languages,
        )
        sql("INSERT INTO language_learning_listening_set VALUES(10,1,'2026-10-03','ja','DICTATION','COMPLETED',1,?,'2026-10-03','2026-10-03')", languages)
        sql("INSERT INTO language_learning_listening_session VALUES(20,1,10,'synthetic','COMPLETED',1,'{}','2026-10-03','2026-10-03')")
        sql("INSERT INTO language_learning_level_test_session(id,session_uid,user_id,session_type,status,origin_language,learning_language,timezone,current_question_number,current_complexity_band,domain_scores_json,started_at,last_activity_at,completed_at,completed_date,idempotency_key) VALUES(30,'synthetic',1,'INITIAL','COMPLETED','ko','en','Asia/Seoul',20,3,'{}','2026-10-02','2026-10-02','2026-10-02','2026-10-03','synthetic')")
    }
}
