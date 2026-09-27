package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelItem
import jp.co.translacat.languagelearning.features.leveltest.support.LevelFixtures
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LevelGenerationHistoryTest {
    private val now = LocalDateTime.parse("2026-09-26T12:00:00")
    private val session = LevelFixtures.session(now)
    private fun own(index: Int, time: LocalDateTime = now.minusMinutes(index.toLong())): LevelItem {
        val data = LevelFixtures.question(session, 1)
        return LevelItem(
            id = index + 1L, sessionId = session.id, questionNumber = 1,
            data = data.copy(
                promptText = "Synthetic content $index",
                diversityMetadata = data.diversityMetadata.copy(contentHash = "own-$index"),
            ),
            createdAt = time,
        )
    }

    private fun other(index: Int, time: LocalDateTime = now.minusMinutes(index.toLong()).minusSeconds(30)) =
        LevelHistoryEntry(time, buildJsonObject { put("sourceType", "WRITING"); put("contentHash", "other-$index") })

    private fun JsonObject.hashes(key: String) = getValue(key).jsonArray.map {
        if (it is JsonObject) it.getValue("contentHash").jsonPrimitive.content else it.jsonPrimitive.content
    }

    @Test
    fun `장기 이력은 최신40 80 교차40 정확200의 원본 상한을 유지한다`() {
        // 준비: 입력 정렬에 의존하지 않고 오래된210문항과 타 기능50문항을 함께 전달한다.
        val own = (0..209).map { own(it) }.reversed()
        val others = (0..49).map { other(it) }.reversed()

        // 실행
        val result = LevelGenerationHistory.context(own.filter { it.id <= 60 }, own, others, now)

        // 검증: 전 기능 시각순200개이므로 서로 다른 기능의 이력이 먼저 들어와도 개수 기준은 변하지 않는다.
        assertEquals((0..39).map { "own-$it" }, result.hashes("currentSession"))
        assertEquals((0..79).map { "own-$it" }, result.hashes("sameFeatureRecent"))
        assertEquals((0..39).map { "other-$it" }, result.hashes("crossFeatureRecent"))
        assertEquals(200, result.hashes("exactContentHashes90d").size)
        assertEquals("own-149", result.hashes("exactContentHashes90d").last())
        assertFalse("own-150" in result.hashes("exactContentHashes90d"))
    }

    @Test
    fun `기간 경계를 포함하고 오래된 같은기능 교차기능은 정확일치 이력에만 남긴다`() {
        // 준비
        val own = listOf(
            own(1, now.minusDays(30)), own(2, now.minusDays(30).minusSeconds(1)), own(3, now.minusDays(90)),
            own(4, now.minusDays(90).minusSeconds(1)),
        )
        val others = listOf(
            other(1, now.minusDays(14)), other(2, now.minusDays(14).minusSeconds(1)), other(3, now.minusDays(90)),
            other(4, now.minusDays(90).minusSeconds(1)),
        )

        // 실행
        val result = LevelGenerationHistory.context(emptyList(), own, others, now)

        // 검증
        assertEquals(listOf("own-1"), result.hashes("sameFeatureRecent"))
        assertEquals(listOf("other-1"), result.hashes("crossFeatureRecent"))
        assertEquals(
            setOf("own-1", "own-2", "own-3", "other-1", "other-2", "other-3"),
            result.hashes("exactContentHashes90d").toSet(),
        )
    }
}
