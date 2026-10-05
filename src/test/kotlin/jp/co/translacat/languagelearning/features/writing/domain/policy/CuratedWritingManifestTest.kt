package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.*

internal class CuratedWritingManifestTest {
    private val source = checkNotNull(javaClass.classLoader.getResourceAsStream("writing/curated-candidates.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `150개 후보는 정확한 셀과 hash를 가지되 사람 승인은 없다`() {
        // 준비·실행: 제품에 동봉된 후보팩을 원문 그대로 읽는다.
        val manifest = CuratedWritingManifestCodec.parse(source)

        // 검증: 15개 셀에 각 10개가 있으며 모든 후보는 승인 전 상태다.
        assertEquals(150, manifest.items.size)
        assertEquals(15, manifest.items.groupBy { it.writingType to it.band }.size)
        assertEquals(setOf(10), manifest.items.groupBy { it.writingType to it.band }.values.map { it.size }.toSet())
        assertEquals(setOf(CuratedReviewStatus.DRAFT), manifest.items.map { it.status }.toSet())
    }

    @Test
    fun `내용 변조와 승인 없는 공개 선택을 거절한다`() {
        // 준비: 첫 참고 답안만 바꾸고 contentHash는 그대로 두어 내용 무결성을 확인한다.
        val original = Json.parseToJsonElement(source).jsonObject
        val items = original.getValue("items").jsonArray
        val first = items.first().jsonObject
        val references = first.getValue("referenceAnswers").jsonArray
        val changedReferences = JsonArray(
            listOf(JsonPrimitive(references.first().jsonPrimitive.content + "改ざん")) + references.drop(1),
        )
        val changedFirst = JsonObject(first + ("referenceAnswers" to changedReferences))
        val changedItems = JsonArray(listOf(changedFirst) + items.drop(1))
        val changed = JsonObject(original + ("items" to changedItems)).toString()

        // 실행·검증: 버전이 동일한 내용 변경은 명시적 실패다.
        assertFailsWith<IllegalArgumentException> { CuratedWritingManifestCodec.parse(changed) }
        val manifest = CuratedWritingManifestCodec.parse(source)
        assertNull(CuratedWritingSelection.select(manifest.items, manifest.releaseId, "ko", "ja",
            WritingType.TRANSLATION, 3, emptySet(), false, "test"))
        assertNotNull(CuratedWritingSelection.select(manifest.items, manifest.releaseId, "ko", "ja",
            WritingType.TRANSLATION, 3, emptySet(), false, "test", qaOnly = true))
    }

    @Test
    fun `세 유형 다섯 단계의 사전 고정 100 seed는 QA에서 온전한 다섯 문항을 고른다`() {
        // 준비: 실제 제품 후보팩과 고정된 100개 선택 seed를 사용한다.
        val manifest = CuratedWritingManifestCodec.parse(source)
        val types = WritingType.entries

        // 실행·검증: 각 셀에서 전체 조합을 찾고 ID·의미 중복 없이 정확한 목표 band를 지킨다.
        for (type in types) for (band in 1..5) for (seed in 0 until 100) {
            val selection = CuratedWritingSelection.select(manifest.items, manifest.releaseId,
                "ko", "ja", type, band, emptySet(), false, "fixed-$seed", qaOnly = true)
            assertNotNull(selection, "$type B$band seed=$seed")
            assertEquals(5, selection.slots.size)
            assertEquals(5, selection.slots.map { it.second.id }.distinct().size)
            assertEquals(5, selection.slots.map { it.second.semanticKey }.distinct().size)
            assertEquals(CuratedWritingSelection.slots(band).map { it.targetBand },
                selection.slots.map { it.second.band })
        }
    }

    @Test
    fun `별도 소유자 승인 기록은 정확한 문항 버전과 내용 hash에만 적용된다`() {
        val manifest = CuratedWritingManifestCodec.parse(source)
        val empty = checkNotNull(javaClass.classLoader.getResourceAsStream("writing/curated-approvals.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertEquals(emptyMap(), CuratedWritingApprovalCodec.parse(empty, manifest))

        val item = manifest.items.first()
        fun approval(hash: String) = buildJsonObject {
            put("releaseId", manifest.releaseId)
            putJsonArray("approvals") {
                add(buildJsonObject {
                    put("id", item.id); put("version", item.version); put("contentHash", hash)
                    put("releaseId", item.releaseId); put("approver", "owner-review")
                    put("approvedAt", "2026-10-04T00:00:00Z")
                })
            }
        }.toString()
        assertEquals(item.contentHash,
            CuratedWritingApprovalCodec.parse(approval(item.contentHash), manifest)[item.id to item.version]?.contentHash)
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingApprovalCodec.parse(approval("0".repeat(64)), manifest)
        }
    }
}
