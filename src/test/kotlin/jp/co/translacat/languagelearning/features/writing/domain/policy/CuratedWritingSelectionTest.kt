package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class CuratedWritingSelectionTest {
    private fun item(id: String, band: Int, meaning: String = id, status: CuratedReviewStatus = CuratedReviewStatus.APPROVED) =
        CuratedWritingItem(
            id, 1, "a".repeat(64), status, "ko-ja-1", "ko", "ja", WritingType.FREE,
            band, meaning, "daily", "자유롭게 답하세요.", emptyList(), emptyList(), emptyList(),
            listOf("목적을 전한다"), listOf("提案します。", "別の案もあります。"), "다른 입장도 유효합니다.",
            "과제의 직접적인 기능을 연습한다.", "別の表現もできます。", "答えがありません。", "提案もできます。",
        )

    @Test
    fun `다섯 슬롯은 단계 경계에서도 정확한 band를 요구한다`() {
        // 준비: 양끝 단계의 슬롯을 확인한다.
        val first = CuratedWritingSelection.slots(1)
        val last = CuratedWritingSelection.slots(5)

        // 실행·검증: REVIEW와 CHALLENGE만 경계에서 고정된다.
        assertEquals(listOf(1, 1, 1, 1, 2), first.map { it.targetBand })
        assertEquals(listOf(4, 5, 5, 5, 5), last.map { it.targetBand })
    }

    @Test
    fun `전체 조합을 찾아 의미 중복과 최근 문항을 피한다`() {
        // 준비: band 2의 한 후보가 band 3 후보와 의미가 같아도 대체 조합이 있다.
        val items = listOf(
            item("lower-a", 2, "shared"), item("lower-b", 2, "unique-lower"),
            item("normal-a", 3, "shared"), item("normal-b", 3), item("normal-c", 3),
            item("normal-d", 3), item("normal-e", 3, status = CuratedReviewStatus.DRAFT),
            item("upper-a", 4),
        )

        // 실행: 승인 버전만 선택한다.
        val selection = CuratedWritingSelection.select(
            items, "ko-ja-1", "ko", "ja", WritingType.FREE, 3,
            setOf("normal-d"), false, "fixed-seed",
        )

        // 검증: 다섯 개를 한 번에 고르고 중복 의미가 없다.
        assertNotNull(selection)
        assertEquals(5, selection.slots.size)
        assertEquals(5, selection.slots.map { it.second.semanticKey }.distinct().size)
        assertEquals("lower-b", selection.slots.first().second.id)
        assertEquals("upper-a", selection.slots.last().second.id)
    }

    @Test
    fun `부족할 때 부분 세트를 반환하거나 미승인 후보로 채우지 않는다`() {
        // 준비: normal 슬롯 후보가 두 개뿐이다.
        val items = listOf(item("lower-a", 2), item("normal-a", 3), item("normal-b", 3), item("upper-a", 4))

        // 실행·검증: 다른 단계와 미승인 항목을 끌어오지 않는다.
        assertNull(CuratedWritingSelection.select(items, "ko-ja-1", "ko", "ja", WritingType.FREE, 3,
            emptySet(), false, "seed"))
        assertNull(CuratedWritingSelection.select(items, "ko-ja-1", "ko", "en", WritingType.FREE, 3,
            emptySet(), false, "seed"))
    }

    @Test
    fun `명시적 재학습과 QA 후보는 각각 별도 입력으로만 허용한다`() {
        // 준비: 다섯 후보는 최근 문항이며 사람 승인이 없다.
        val items = (1..5).map { index ->
            item("candidate-$index", if (index == 1) 2 else if (index == 5) 4 else 3,
                status = CuratedReviewStatus.AUTO_REVIEWED)
        }
        val recent = items.map { it.id }.toSet()

        // 실행·검증: 두 플래그가 모두 명시돼야 검증 전용 재학습이 가능하다.
        assertNull(CuratedWritingSelection.select(items, "ko-ja-1", "ko", "ja", WritingType.FREE, 3,
            recent, false, "seed", qaOnly = true))
        assertNull(CuratedWritingSelection.select(items, "ko-ja-1", "ko", "ja", WritingType.FREE, 3,
            recent, true, "seed"))
        assertNotNull(CuratedWritingSelection.select(items, "ko-ja-1", "ko", "ja", WritingType.FREE, 3,
            recent, true, "seed", qaOnly = true))
    }
}
