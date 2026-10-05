package jp.co.translacat.languagelearning.features.overview

import jp.co.translacat.languagelearning.features.growth.domain.model.LearningEvidenceRecord
import jp.co.translacat.languagelearning.features.overview.application.LearningEvidenceQuery
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.support.MemoryGrowthUnitOfWork
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.LocalDate
import kotlin.test.*

class LearningEvidenceQueryTest {
    private val day = LocalDate.parse("2026-10-03")
    private fun record(id: Long, kind: String = "SESSION_COACHING", language: String? = "en") = LearningEvidenceRecord(
        id, "SPEAKING", day, language, "ko", "Synthetic session", kind,
        if (kind == "SESSION_COACHING") "free-session-coaching-v1" else null, "COMPLETED", "COMPLETED",
    )

    @Test
    fun `소유자 언어 정책 기간으로 저장 기록만 읽고 코칭 점수는 만들지 않는다`() = runBlocking {
        // 준비: 서로 다른 owner와 언어/결과 정책을 섞는다.
        val db = MemoryGrowthUnitOfWork()
        db.state.learningEvidenceRows[1] = listOf(record(1), record(2, language = "ja"), record(3, "SCORED_EVALUATION"))
        db.state.learningEvidenceRows[2] = listOf(record(4))
        val query = LearningEvidenceQuery(db)

        // 실행
        val page = query.execute(1, null, "en", day, day, "SESSION_COACHING", "free-session-coaching-v1", null, 25)
        val repeat = query.execute(1, null, "en", day, day, "SESSION_COACHING", "free-session-coaching-v1", null, 25)

        // 검증: 조회 재진입은 같은 저장 자료만 반환하고 Growth를 변경하지 않는다.
        assertEquals(page, repeat)
        val item = page.getValue("items").jsonArray.single().jsonObject
        assertEquals("SPEAKING:-1", item.getValue("activityId").jsonPrimitive.content)
        assertFalse("overallScore" in item)
        assertEquals("SESSION_COACHING", item.getValue("resultKind").jsonPrimitive.content)
        assertTrue(db.state.activities.isEmpty())
        assertTrue(db.state.profiles.isEmpty())
        assertTrue(query.execute(3, null, null, day, day, null, null, null, 25).getValue("items").jsonArray.isEmpty())
    }

    @Test
    fun `안정된 cursor는 중간 삭제 후 누락 없이 이어지고 다른 소유자 필터 재사용은 거절한다`(): Unit = runBlocking {
        // 준비
        val db = MemoryGrowthUnitOfWork()
        db.state.learningEvidenceRows[1] = (1L..4L).map { record(it) }
        val query = LearningEvidenceQuery(db)
        val first = query.execute(1, null, null, day, day, null, null, null, 2)
        val cursor = first.getValue("nextCursor").jsonPrimitive.content

        // 실행: 이미 조회한 원본이 삭제되어도 OFFSET처럼 뒤의 행을 건너뛰지 않는다.
        db.state.learningEvidenceRows[1] = listOf(record(1), record(2), record(3))
        val second = query.execute(1, null, null, day, day, null, null, cursor, 2)

        // 검증
        assertEquals(listOf("SPEAKING:-2", "SPEAKING:-1"), second.getValue("items").jsonArray.map {
            it.jsonObject.getValue("activityId").jsonPrimitive.content
        })
        assertEquals(JsonNull, second.getValue("nextCursor"))
        assertFailsWith<LearningBusinessException> { query.execute(2, null, null, day, day, null, null, cursor, 2) }
        assertFailsWith<LearningBusinessException> { query.execute(1, null, "ja", day, day, null, null, cursor, 2) }
    }

    @Test
    fun `알 수 없는 metadata를 현재 정책이나 언어로 채우지 않으며 기술 상한을 거절한다`(): Unit = runBlocking {
        // 준비
        val db = MemoryGrowthUnitOfWork()
        db.state.learningEvidenceRows[1] = listOf(record(1, "UNKNOWN", null))
        val query = LearningEvidenceQuery(db)

        // 실행 및 검증
        val item = query.execute(1, null, null, day, day, null, null, null, 25).getValue("items").jsonArray.single().jsonObject
        assertEquals(JsonNull, item["learningLanguage"])
        assertEquals(JsonNull, item["policyVersion"])
        assertEquals("LIMITED", item.getValue("availability").jsonPrimitive.content)
        assertTrue(item.getValue("limitations").jsonArray.contains(JsonPrimitive("RESULT_KIND_UNKNOWN")))
        for (limit in listOf(0, 51)) {
            assertFailsWith<LearningBusinessException> { query.execute(1, null, null, day, day, null, null, null, limit) }
        }
        assertFailsWith<LearningBusinessException> { query.execute(1, null, null, day.minusDays(366), day, null, null, null, 25) }
        assertFailsWith<LearningBusinessException> { query.execute(1, null, null, day, day, "FUTURE_SCORE", null, null, 25) }
        assertFailsWith<LearningBusinessException> { query.execute(1, null, null, day, day, null, null, "invalid", 25) }
    }
}
