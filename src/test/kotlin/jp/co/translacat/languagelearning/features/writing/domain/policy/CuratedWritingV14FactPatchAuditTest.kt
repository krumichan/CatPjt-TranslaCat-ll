package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingV14FactPatchAuditTest {
    @Test
    fun `같은 넓은 문장에서 정상 요일은 보존하고 잘못된 연락 수단만 교정한다`() {
        // 준비: N03의 정확한 원문·공개 과제를 사용한다. 18시와 전화는 오류이고 다음 주 월요일은 정상이다.
        val (plan, verified) = deadlineAndChannel()
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        assertEquals(2, repairs.tasks.size)
        val channel = repairs.tasks.single { it.kind == "NATURAL_FACT" }
        val replacement = "翌週月曜日に結果をメールで受け取れます。"
        val raw = CuratedWritingFeedbackV13TestPayload.correction("C3", channel.segmentId,
            channel.original, replacement, "T1")

        // 실행: 구형 행 검사의 과잉 변경 요구와 새 보존 검사를 같은 원본으로 비교한다.
        assertFalse(CuratedWritingFeedbackV13Policy.contentRowRepairsItsFact(raw, plan, verified))
        assertTrue(CuratedWritingV14FactPatchAudit.valid(raw, plan, verified))
        val core = CuratedWritingFeedbackV14Policy.assembleCore(plan, verified, repairs,
            CuratedWritingFeedbackV14TestPayload.output(repairs, mapOf(channel.id to replacement)))

        // 검증: 마감 한 건과 연락 한 건만 있으며 원래 관찰은 UNMET, 정상 요일은 한 번 그대로 남는다.
        val corrections = core.getValue("necessaryCorrections").jsonArray
        assertEquals(2, corrections.size)
        var full = plan.segments.single().text
        repairs.tasks.sortedByDescending { it.start }.forEach { task ->
            val row = corrections.single { it.jsonObject.getValue("patch").jsonObject.string("original") == task.original }.jsonObject
            full = full.replaceRange(task.start, task.end, row.getValue("patch").jsonObject.string("replacement"))
        }
        assertEquals("申請書を今週金曜日の17時までに提出すれば、翌週月曜日に結果をメールで受け取れます。", full)
        assertTrue(core.getValue("observations").jsonArray.all { it.jsonObject.string("status") == "UNMET" })
        assertEquals(core, CuratedWritingFeedbackV14Policy.validateCoreResult(plan, verified, core))
    }

    @Test
    fun `정상 요일을 삭제하거나 바꾸는 연락 수단 교정은 정상 작업으로 고정하지 않는다`() {
        // 준비: 전화만 고칠 권한이 있는 실제 수리 범위를 사용한다.
        val (plan, verified) = deadlineAndChannel()
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        val channel = repairs.tasks.single { it.kind == "NATURAL_FACT" }

        // 실행·검증: 정상 월요일을 삭제·화요일 변경·복수 대안으로 만드는 교정은 모두 거절한다.
        for (replacement in listOf("結果をメールで受け取れます。", "翌週火曜日に結果をメールで受け取れます。",
            "翌週月曜日または翌週火曜日に結果をメールで受け取れます。")) {
            val output = CuratedWritingFeedbackV14TestPayload.output(repairs, mapOf(channel.id to replacement))
            val progress = CuratedWritingFeedbackV14Policy.salvageCore(plan, verified, repairs, output)
            assertEquals(listOf(channel.id), progress.unresolvedTaskIds)
            assertTrue(progress.frozenOutput.getValue("repairs").jsonObject.containsKey(repairs.tasks.first().id))
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingFeedbackV14Policy.assembleCore(plan, verified, repairs, output)
            }
        }
    }

    @Test
    fun `명시 오류는 정답 추가만으로 수리되지 않고 원래 잘못된 값도 사라져야 한다`() {
        // 준비: 공유 마감 사실의 실제 18시 위치를 사용한다.
        val (plan, verified) = deadlineAndChannel()
        val fact = plan.contract.facts.single { it.key == "deadline_time" }
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        val task = repairs.tasks.single { it.kind == "FACT" }

        // 실행·검증: 17시의 존재만 검사하지 않고 같은 변경 구역의 canonical 값 전체를 대조한다.
        for (replacement in listOf("18時または17時", "17時または18時")) {
            val row = CuratedWritingFeedbackV13TestPayload.correction("C1", task.segmentId, task.original, replacement, "T1")
            assertFalse(CuratedWritingV14FactPatchAudit.valid(row, plan, verified))
            assertFalse(CuratedWritingV14FactPatchAudit.repairsExplicitConflict(row, plan, verified, fact))
        }
        val row = CuratedWritingFeedbackV13TestPayload.correction("C1", task.segmentId, task.original, "17時", "T1")
        assertTrue(CuratedWritingV14FactPatchAudit.valid(row, plan, verified))
        assertTrue(CuratedWritingV14FactPatchAudit.repairsExplicitConflict(row, plan, verified, fact))
    }

    @Test
    fun `다른 사실의 정상 값 보존 검사도 연락 수단 행 권한과 분리해 유지한다`() {
        // 준비: 한 T1에 여러 슬롯이 있다는 이유로 정상 요일을 수리 목표로 전환하지 않는다.
        val (plan, verified) = deadlineAndChannel()
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        val channel = repairs.tasks.single { it.kind == "NATURAL_FACT" }
        val valid = CuratedWritingFeedbackV14TestPayload.output(repairs,
            mapOf(channel.id to "翌週月曜日に結果を電子メールで受け取れます。"))

        // 실행: 결정적 마감은 고정하고 정상 연락 수단 수리를 한 번의 전체 검증으로 완료한다.
        val progress = CuratedWritingFeedbackV14Policy.salvageCore(plan, verified, repairs, valid)

        // 검증: 다른 정상 슬롯을 억지로 변경하는 추가 요청이나 재수리가 필요하지 않다.
        assertNotNull(progress.completedResult)
        assertTrue(progress.unresolvedTaskIds.isEmpty())
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV14Policy.repairRemainingSchema(repairs, progress) }
    }

    @Test
    fun `일반 발화 인용 안의 정상 책 제목은 인용 전체를 제목으로 오인하지 않는다`() {
        // 준비: 실제 제목 문법이 아닌 발화 인용을 사용한다. 이 단위 검사는 표현 교정의 유용성까지 승인하지 않는다.
        val plan = CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-guided-b3-02",
            "「私は星の旅を借りています」とお伝えします。返却日は6月15日ですが、まだ読み終えていません。6月22日まで延長できますか。可能なら、その日に変更してください。")
        val verified = CuratedWritingFeedbackV13TestPayload.verified(plan)
        val original = plan.segments.first().text
        val row = CuratedWritingFeedbackV13TestPayload.correction("C1", "S1", original,
            "「星の旅を借りています」と伝えます。", "")

        // 실행·검증: 실제 차이 범위 안에 남은 정상 title의 canonical 값은 그대로다.
        CuratedWritingFeedbackV13Policy.validateVerification(verified, plan)
        assertTrue(CuratedWritingV14FactPatchAudit.valid(row, plan, verified))
    }

    @Test
    fun `명시 책 제목의 접두 부분만 일치하는 잘못된 제목은 보존 성공이 아니다`() {
        // 준비: 원래 정상 제목을 다른 제목으로 바꾼 결과에서 별도 제목의 prefix만 추출하지 못하게 한다.
        val plan = CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-guided-b3-02",
            "借りている『星の旅』の返却日は6月15日です。まだ読み終えていません。6月22日まで延長できますか。可能なら、その日に変更してください。")
        val verified = CuratedWritingFeedbackV13TestPayload.verified(plan)
        val original = plan.segments.first().text

        // 실행·검증: 큰 꺾쇠와 제목 구문에 붙은 작은 꺾쇠 모두 전체 제목이 일치해야 한다.
        for (replacement in listOf("借りている『星の旅行』の返却日は6月15日です。", "借りている「星の旅行」の返却日は6月15日です。")) {
            val row = CuratedWritingFeedbackV13TestPayload.correction("C1", "S1", original, replacement, "T1")
            assertFalse(CuratedWritingV14FactPatchAudit.valid(row, plan, verified))
        }
    }

    @Test
    fun `정상 월요일의 알려진 별칭은 의미를 보존한 연락 수단 교정으로 허용한다`() {
        // 준비: 고정 요일의 공개 정규화 별칭만 사용한다. expected나 판정 기준은 변경하지 않는다.
        val (plan, verified) = deadlineAndChannel()
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        val channel = repairs.tasks.single { it.kind == "NATURAL_FACT" }

        // 실행: 날짜를 다른 사실로 바꾸지 않는 정상 별칭과 올바른 이메일 수단을 함께 확인한다.
        val core = CuratedWritingFeedbackV14Policy.assembleCore(plan, verified, repairs,
            CuratedWritingFeedbackV14TestPayload.output(repairs,
                mapOf(channel.id to "来週の月曜に結果を電子メールで受け取れます。")))

        // 검증: 별칭을 unknown 요일로 오인하지 않고 전체 필수 교정 두 건을 유지한다.
        assertEquals(2, core.getValue("necessaryCorrections").jsonArray.size)
    }

    @Test
    fun `원래 다른 사건의 화요일은 새로 도입된 날짜로 오인하지 않는다`() {
        // 준비: 같은 원문 절 안의 별도 일정은 바꾸지 않고 결과 수단만 교정한다.
        val original = "翌週火曜日の予定とは別に翌週月曜日に結果を電話で受け取れます。"
        val (plan, verified) = deadlineAndChannel(original)
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        val channel = repairs.tasks.single { it.kind == "NATURAL_FACT" }

        // 실행: 다른 사건의 요일과 정상 결과 요일을 모두 원문대로 보존한다.
        val core = CuratedWritingFeedbackV14Policy.assembleCore(plan, verified, repairs,
            CuratedWritingFeedbackV14TestPayload.output(repairs,
                mapOf(channel.id to original.replace("電話", "メール"))))

        // 검증: 새 요일 대안 추가 거절과 기존 별도 사건 보존을 구별한다.
        assertTrue(core.getValue("necessaryCorrections").jsonArray.any {
            it.jsonObject.getValue("patch").jsonObject.string("replacement").contains("翌週火曜日の予定")
        })
    }

    private fun deadlineAndChannel(result: String = "翌週月曜日に結果を電話で受け取れます。"):
        Pair<CuratedWritingFeedbackV13Policy.Plan, JsonObject> {
        val plan = CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-translation-b3-01",
            "申請書を今週金曜日の18時までに提出すれば、" + result)
        val second = plan.sources.single { it.text.contains("電話") }
        val initial = CuratedWritingFeedbackV13TestPayload.verified(plan)
        val atoms = JsonObject(initial.getValue("atoms").jsonObject +
            ("C3.A1" to CuratedWritingFeedbackV13TestPayload.edge("CONFLICT", listOf(second.id))))
        val facts = JsonObject(initial.getValue("facts").jsonObject + ("result_channel" to buildJsonObject {
            put("sourceId", second.id); put("valueToken", "NONE"); put("slotRelation", "IMPLICIT_CONTEXT")
        }))
        val verified = JsonObject(initial + mapOf("atoms" to atoms, "facts" to facts))
        return plan to CuratedWritingFeedbackV13Policy.validateVerification(verified, plan)
    }
}

private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
