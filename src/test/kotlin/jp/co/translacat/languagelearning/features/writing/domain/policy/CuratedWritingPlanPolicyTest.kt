package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingDifficulty
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlin.test.*

class CuratedWritingPlanPolicyTest {
    @Test
    fun `목표 수와 경계 단계 모두 합계와 고정 비율 반올림을 보존한다`() {
        // 준비: 100은 시험값이며 더 큰 생성자 상한도 같은 정책을 사용한다.
        val targets = listOf(1, 2, 3, 5, 10, 20, 30, 50, 100, 101)

        // 실행·검증: 모든 목표에서 정수 배분 합계와 clamp 후 원래 범주가 유지된다.
        targets.forEach { target ->
            val counts = CuratedWritingPlanPolicy.distribution(target)
            assertEquals(target, counts.values.sum())
            for (band in 1..5) {
                val slots = CuratedWritingPlanPolicy.slots(target, band)
                assertEquals(target, slots.size)
                assertEquals(counts, WritingDifficulty.entries.associateWith { kind -> slots.count { it.difficulty == kind } })
                slots.forEach {
                    val expected = when (it.difficulty) {
                        WritingDifficulty.REVIEW -> (band - 1).coerceAtLeast(1)
                        WritingDifficulty.NORMAL -> band
                        WritingDifficulty.CHALLENGE -> (band + 1).coerceAtMost(5)
                    }
                    assertEquals(expected, it.targetBand)
                }
            }
        }
        assertEquals(mapOf(WritingDifficulty.REVIEW to 1, WritingDifficulty.NORMAL to 1,
            WritingDifficulty.CHALLENGE to 0), CuratedWritingPlanPolicy.distribution(2))
        assertEquals(listOf(1, 3, 1), CuratedWritingPlanPolicy.distribution(5).values.toList())
        assertEquals(Int.MAX_VALUE, CuratedWritingPlanPolicy.distribution(Int.MAX_VALUE).values.sum())
    }

    @Test
    fun `확대는 기존 슬롯을 보존하고 작은 목표의 반올림 편차를 숨기지 않는다`() {
        // 준비
        val old = CuratedWritingPlanPolicy.slots(10, 3)

        // 실행
        val expanded = CuratedWritingPlanPolicy.slots(30, 3, old)

        // 검증
        assertEquals(old, expanded.take(10))
        assertEquals(listOf(6, 18, 6), WritingDifficulty.entries.map { kind -> expanded.count { it.difficulty == kind } })
        assertFailsWith<IllegalArgumentException> { CuratedWritingPlanPolicy.slots(9, 3, old) }
        assertFailsWith<IllegalArgumentException> { CuratedWritingPlanLimits().requireTarget(101) }
        CuratedWritingPlanLimits(maxTargetItemCount = 101).requireTarget(101)
    }

    @Test
    fun `최대 매칭은 부족 항목만 남기고 의미 중복과 후보 버전 중복을 선택하지 않는다`() {
        // 준비: 슬롯 간 의미 키 경쟁이 있어도 확보 가능한 수를 최대화한다.
        val first = CuratedWritingPlanFixtures.item(WritingType.TRANSLATION, 2, 1).copy(semanticKey = "shared")
        val alternative = CuratedWritingPlanFixtures.item(WritingType.TRANSLATION, 2, 2)
        val second = CuratedWritingPlanFixtures.item(WritingType.TRANSLATION, 3, 1).copy(semanticKey = "shared")
        val slots = listOf(CuratedSlot(1, WritingDifficulty.REVIEW, 2), CuratedSlot(2, WritingDifficulty.NORMAL, 3),
            CuratedSlot(3, WritingDifficulty.CHALLENGE, 4))

        // 실행
        val selected = CuratedWritingPlanPolicy.select(slots, listOf(first, first.copy(version = 2), alternative, second), "seed")

        // 검증: 반복 결과 결정성, 최대2개, 중복0.
        assertEquals(2, selected.slots.size)
        assertEquals(setOf(alternative.id, second.id), selected.slots.map { it.second.id }.toSet())
        assertEquals(selected, CuratedWritingPlanPolicy.select(slots,
            listOf(second, alternative, first.copy(version = 2), first), "seed"))
    }

    @Test
    fun `백개 문항 선택은 합성 재고에서 전부 확보하고 부족한 세개만 남긴다`() {
        // 준비
        val full = CuratedWritingPlanFixtures.manifest(100).items
        val slots = CuratedWritingPlanPolicy.slots(100, 3)

        // 실행·검증
        val selected = CuratedWritingPlanPolicy.select(slots, full.filter { it.writingType == WritingType.FREE }, "hundred")
        assertEquals(100, selected.slots.size)
        assertEquals(100, selected.slots.map { it.second.semanticKey }.distinct().size)
        val partial = CuratedWritingPlanFixtures.manifest(6).items.filter { it.writingType == WritingType.FREE }
            .filter { it.band != 3 } + (1..15).map { CuratedWritingPlanFixtures.item(WritingType.FREE, 3, it) }
        assertEquals(27, CuratedWritingPlanPolicy.select(CuratedWritingPlanPolicy.slots(30, 3), partial, "partial").slots.size)
    }
}

/** 기술 구조 검증 전용 합성 재고이며 제품 manifest나 사람 승인을 변경하지 않는다. */
internal object CuratedWritingPlanFixtures {
    fun manifest(perBand: Int) = CuratedWritingManifest("synthetic-variable-n", WritingType.entries.flatMap { type ->
        (1..5).flatMap { band -> (1..perBand).map { item(type, band, it) } }
    })

    fun item(type: WritingType, band: Int, index: Int): CuratedWritingItem {
        val id = "synthetic-${type.name.lowercase()}-b$band-$index"
        return CuratedWritingItem(id, 1, CuratedWritingManifestCodec.sha256(id), CuratedReviewStatus.DRAFT,
            "synthetic-variable-n", "ko", "ja", type, band, id, "topic-$index",
            "합성 기술 테스트 $id. 안내를 읽고 답안을 작성하세요.",
            if (type == WritingType.GUIDED) listOf("합성 사실 $index") else emptyList(),
            if (type == WritingType.GUIDED) listOf("합성 요청") else emptyList(),
            if (type == WritingType.GUIDED) listOf("합성 조건") else emptyList(),
            listOf("합성 점검"), listOf("合成の参考回答です。", "別の合成回答です。"), "다른 답도 가능",
            "합성 검증이지 품질 판정 아님", "대안", "오류", "경계")
    }
}
