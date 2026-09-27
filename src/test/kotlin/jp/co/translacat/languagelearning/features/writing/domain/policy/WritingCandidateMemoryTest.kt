package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WritingCandidateMemoryTest {
    private val golden = Json.parseToJsonElement(
        requireNotNull(
            javaClass.getResource(
                "/contracts/writing-source-recovery-python-golden.json",
            ),
        ).readText(),
    ).jsonObject

    @Test
    fun `확정 거부 문항은 노트만 바꾸어도 다시 검증하지 않는다`() {
        // 준비: 같은 학습 문항에 서로 다른 노트와 분류를 부여한다.
        val request = golden.getValue("request").jsonObject
        val draft = WritingCandidatePolicy.parse(golden.getValue("localized").jsonObject)
        val noteOnly = draft.copy(focusReason = "다른 학습 설명입니다.")
        val memory = WritingCandidateMemory()

        // 실행: 첫 후보의 확정 품질 거부를 기록한 뒤 노트 변경 후보를 검사한다.
        assertNull(memory.check(request, draft))
        memory.recordRejection(draft, "QUALITY_AMBIGUOUS_TASK")
        val result = memory.check(request, noteOnly)

        // 검증: 후보 내용 해시가 달라도 문항 자체의 거부는 유지한다.
        assertEquals("REPEATED_REJECTED_TASK", result)
    }

    @Test
    fun `노트 거부는 확정 거부가 아니며 원문 복구 증거는 별도 후보를 구별한다`() {
        // 준비: 동일 후보의 노트 수정과 서로 다른 원문 복구 결합을 만든다.
        val request = golden.getValue("request").jsonObject
        val draft = WritingCandidatePolicy.parse(golden.getValue("localized").jsonObject)
        val noteOnly = draft.copy(focusReason = "다른 학습 설명입니다.")
        val memory = WritingCandidateMemory()

        // 실행: 노트 품질 거부 후 수정 후보와 별도 원문 복구 근거를 순서대로 검사한다.
        assertNull(memory.check(request, draft))
        memory.recordRejection(draft, "QUALITY_FOCUS_REASON")
        val changedNote = memory.check(request, noteOnly)
        val duplicate = memory.check(request, draft)
        val recovered = memory.check(request, draft, "different-source-binding")

        // 검증: 노트 변경은 허용하되 동일 검증과 동일 복구 근거의 반복은 차단한다.
        assertNull(changedNote)
        assertEquals("REPEATED_REJECTED_CANDIDATE", duplicate)
        assertNull(recovered)
        assertEquals(
            "REPEATED_REJECTED_CANDIDATE",
            memory.check(request, draft, "different-source-binding"),
        )
    }
}
