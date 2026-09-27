package jp.co.translacat.languagelearning.features.listening.domain.policy

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListeningProfilePolicyTest {
    private val now = LocalDateTime.parse("2026-09-26T05:00:00")
    private fun signal(id: String, score: Double, confidence: Double = .9, daysAgo: Int = 0) =
        ListeningProfilePolicy.Signal(
            id, score, confidence, "INDEPENDENT", 1.0, true, false, false, now.minusDays(daysAgo.toLong()),
        )

    @Test
    fun `기존 BE recency 도움 confidence 경계와 활동 중복 제거를 보존한다`() {
        // 준비
        val ranks = listOf(1, 5, 6, 10, 11, 20, 21, 30, 31)
        val signals = listOf(
            signal("same", 80.0), signal("same", 90.0, daysAgo = 1),
            signal("low-confidence", 70.0, .69, 2), signal("third", 75.0, daysAgo = 3),
        )

        // 실행
        val weights = ranks.map(ListeningProfilePolicy::recencyWeight)
        val aggregate = ListeningProfilePolicy.aggregate(signals)

        // 검증
        assertEquals(listOf(1.0, 1.0, .85, .85, .70, .70, .55, .55, 0.0), weights)
        assertEquals(.85 * .8 * .85 * .5, ListeningProfilePolicy.finalWeight(6, .8, "ASSISTED", .5))
        assertEquals(0.0, ListeningProfilePolicy.finalWeight(1, 1.0, "GUIDED", 1.0))
        assertEquals(2, aggregate.sampleCount)
        assertNull(aggregate.score)
        assertEquals("DATA_COLLECTING", aggregate.confidence)
    }

    @Test
    fun `성장과 약점은 기존 다섯 활동 창 및 경계값을 적용한다`() {
        // 준비
        val growth = (0 until 10).map { signal("a-$it", if (it < 5) 80.0 else 75.0, daysAgo = it) }
        val weak = listOf(signal("a", 60.0), signal("b", 64.0, daysAgo = 1), signal("c", 70.0, daysAgo = 2))

        // 실행 및 검증
        assertTrue(ListeningProfilePolicy.growth(growth, false).active)
        assertTrue(ListeningProfilePolicy.growth(growth, true).active)
        assertEquals("ACTIVE", ListeningProfilePolicy.weakness(weak).state)
        assertEquals(
            listOf("WEAK_EVIDENCE", "NEUTRAL", "NEUTRAL", "RECOVERY_EVIDENCE"),
            listOf(64.999, 65.0, 74.999, 75.0).map(ListeningProfilePolicy::classify),
        )
    }
}
