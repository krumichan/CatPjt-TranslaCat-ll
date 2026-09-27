package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class WritingDifficultyRecoveryTest {
    @Test
    fun `확정된 같은 방향 불일치 두 건만 남은 한 차례 생성 방향을 선택한다`() {
        // 준비: 목표 B4와 원본 Python 정책의 최대 네 차례 생성 한도를 둔다.
        val recovery = WritingDifficultyRecovery(4)

        // 실행: 검증 종료 뒤 확정 B3 불일치 두 건을 기록한다.
        assertFalse(recovery.record(3, "BORDERLINE", "VERIFIED_BAND_MISMATCH", 1))
        assertTrue(recovery.record(3, "ASSESSED", "VERIFIED_BAND_MISMATCH", 1))
        assertTrue(recovery.record(3, "ASSESSED", "VERIFIED_BAND_MISMATCH", 1))
        assertTrue(recovery.selectAfterRound(1, 4))

        // 검증: 목표 band와 관측 횟수는 바꾸지 않고 다음 한 차례만 제어한다.
        assertEquals("INCREASE_PRODUCTION_DEMAND", recovery.direction)
        assertEquals(
            Json.parseToJsonElement("""{"3":2}"""),
            recovery.payload()?.get("observedBandCounts"),
        )
        assertEquals(1, recovery.payload()?.get("additionalGenerationRounds")?.jsonPrimitive?.int)
        assertFalse(recovery.selectAfterRound(2, 4))
    }

    @Test
    fun `혼합 방향과 마지막 시도의 결과로는 추가 생성 방향을 만들지 않는다`() {
        // 준비: 두 독립 슬롯에 서로 다른 관측 조합을 준비한다.
        val mixed = WritingDifficultyRecovery(3)
        val final = WritingDifficultyRecovery(3)

        // 실행: 혼합 방향과 마지막 시도 확정 불일치를 각각 기록한다.
        mixed.record(2, "ASSESSED", "VERIFIED_BAND_MISMATCH", 2)
        mixed.record(4, "ASSESSED", "VERIFIED_BAND_MISMATCH", 2)
        final.record(4, "ASSESSED", "VERIFIED_BAND_MISMATCH", 4)
        final.record(4, "ASSESSED", "VERIFIED_BAND_MISMATCH", 4)

        // 검증: 표본이 충분해도 방향 충돌이나 남은 시도 부재는 추가 호출을 막는다.
        assertFalse(mixed.selectAfterRound(2, 4))
        assertFalse(final.selectAfterRound(4, 4))
        assertNull(mixed.payload())
        assertNull(final.payload())
    }
}
