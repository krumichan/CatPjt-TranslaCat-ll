package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingFeedbackV14PolicyTest {
    @Test
    fun `귀가의 동등 T P 권한은 원문 위치 하나와 수리 작업 하나를 갖는다`() {
        // 준비: 실제 형식 실패가 있었던 귀가 충돌을 고정하며 의미 검수 성공으로 일반화하지 않는다.
        val (plan, verified) = CuratedWritingFeedbackV14TestPayload.homeConflict()
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)

        // 실행: 모델은 서버가 고정한 범위만 바꾼 전체 segment를 만든다.
        assertEquals(1, repairs.tasks.size)
        val task = repairs.tasks.single()
        assertEquals(setOf("T4", "P2"), task.coverage.map { it.authorityId }.toSet())
        assertNull(CuratedWritingFeedbackV14Policy.serverCoreIfComplete(plan, verified, repairs))
        val output = CuratedWritingFeedbackV14TestPayload.output(repairs, mapOf(task.id to "完成した植木鉢は家に持って帰りました。"))
        val result = CuratedWritingFeedbackV14Policy.assembleCore(plan, verified, repairs, output)

        // 검증: 한 번의 물리적 교정으로 두 권한을 충족하되 원래 답안 상태는 충돌로 남는다.
        val corrections = result.getValue("necessaryCorrections").jsonArray
        assertEquals(1, corrections.size)
        assertEquals("T4", corrections.single().jsonObject.string("sourceFactId"))
        assertEquals("", corrections.single().jsonObject.string("sourceAuthorityId"))
        assertEquals("UNMET", result.observations().getValue("C4").string("status"))
        assertEquals(listOf("PRESENT", "CONFLICT"), result.observations().getValue("C4")
            .getValue("requirementParts").jsonArray.map { it.jsonObject.string("status") })
        assertEquals("curated-writing-feedback-v14", result.string("policyVersion"))
        assertEquals("server-repair-plan-v1", result.string("repairPlanVersion"))
        assertEquals(repairs.hash, result.string("repairPlanHash"))
    }

    @Test
    fun `같은 마감의 C1 C2는 결정적 시각 교정 하나로 끝나며 모델 수리를 요구하지 않는다`() {
        // 준비: 같은 18시를 마감과 조건이 함께 참조한다.
        val plan = CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-translation-b3-01",
            "申請書は今週金曜日の18時までに提出してください。この締切に間に合えば、結果は来週の月曜日に届きます。通知は電子メールで受け取れます。")
        val verified = CuratedWritingFeedbackV13TestPayload.verified(plan)
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)

        // 실행: 실제 token을 공개 값으로 교정한 뒤 기존 전체 검증과 coverage를 전부 적용한다.
        val task = repairs.tasks.single()
        assertEquals(setOf("C1", "C2"), task.coverage.map { it.requirementId }.toSet())
        assertEquals("17時", task.deterministicReplacement)
        assertTrue(repairs.modelTaskIds.isEmpty())
        val result = assertNotNull(CuratedWritingFeedbackV14Policy.serverCoreIfComplete(plan, verified, repairs))

        // 검증: 오류가 있던 두 요구는 그대로 UNMET이며 동일 위치 patch를 중복하지 않는다.
        assertEquals(1, result.getValue("necessaryCorrections").jsonArray.size)
        assertEquals(mapOf("C1" to "UNMET", "C2" to "UNMET", "C3" to "MET"),
            result.observations().mapValues { it.value.string("status") })
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV14Policy.coreSchema(repairs) }
    }

    @Test
    fun `누락 사건에는 수리 작업을 만들지 않고 필수 모델 호출도 생략한다`() {
        // 준비: 참석만 표현하고 귀가 사건은 쓰지 않았다.
        val plan = CuratedWritingFeedbackV13TestPayload.workshop(CuratedWritingFeedbackV14TestPayload.workshopMissing)
        val verified = CuratedWritingFeedbackV13TestPayload.verified(plan)
            .atom("C4.A2", CuratedWritingFeedbackV13TestPayload.edge("ABSENT", emptyList()))

        // 실행: 핵심 결과는 서버가 누락 관찰만 만든다.
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        val result = assertNotNull(CuratedWritingFeedbackV14Policy.serverCoreIfComplete(plan, verified, repairs))

        // 검증: 귀가 추가·optional 발명은 core 단계의 응답 필드가 아니다.
        assertTrue(repairs.tasks.isEmpty())
        assertTrue(result.getValue("necessaryCorrections").jsonArray.isEmpty())
        assertTrue(result.getValue("optionalAlternatives").jsonArray.isEmpty())
        assertEquals("UNMET", result.observations().getValue("C4").string("status"))
        assertEquals("NOT_RUN_LIMIT", result.string("optionalSuggestionsStatus"))
    }

    @Test
    fun `추가 작업과 원문 권한 재선언 및 다른 revision은 거절한다`() {
        // 준비: 서버가 발급한 작업 하나와 올바른 답을 사용한다.
        val (plan, verified) = CuratedWritingFeedbackV14TestPayload.homeConflict()
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        val correct = CuratedWritingFeedbackV14TestPayload.output(repairs, mapOf("R1" to "完成した植木鉢は家に持ち帰りました。"))
        val rows = correct.getValue("repairs").jsonObject
        val additional = JsonObject(correct + ("repairs" to JsonObject(rows + ("R2" to rows.getValue("R1")))))
        val authorityInjection = JsonObject(correct + ("repairs" to JsonObject(mapOf("R1" to
            JsonObject(rows.getValue("R1").jsonObject + ("sourceFactId" to JsonPrimitive("T4")))))))
        val wrongHash = JsonObject(correct + ("repairPlanHash" to JsonPrimitive("0".repeat(64))))

        // 실행·검증: 다른 task, 원문·권한 필드와 다른 계획 hash로 patch 범위를 넓히지 못한다.
        for (invalid in listOf(additional, authorityInjection, wrongHash)) {
            assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV14Policy.assembleCore(plan, verified, repairs, invalid) }
        }
        val other = CuratedWritingFeedbackV13TestPayload.workshop(CuratedWritingFeedbackV14TestPayload.workshopMissing)
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV14Policy.corePrompt(other, verified, repairs) }
    }

    @Test
    fun `정상 작업만 남기면 전체 검사 후 추가 복구 호출을 요구하지 않는다`() {
        // 준비: T/P를 한 R1로 이미 합쳤으므로 중복 행을 다시 생성할 공간이 없다.
        val (plan, verified) = CuratedWritingFeedbackV14TestPayload.homeConflict()
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        val output = CuratedWritingFeedbackV14TestPayload.output(repairs, mapOf("R1" to "完成した植木鉢は家に持ち帰りました。"))

        // 실행·검증: salvage도 전체 guard를 통과한 completedResult를 반환한다.
        val progress = CuratedWritingFeedbackV14Policy.salvageCore(plan, verified, repairs, output)
        assertNotNull(progress.completedResult)
        assertTrue(progress.unresolvedTaskIds.isEmpty())
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV14Policy.repairRemainingSchema(repairs, progress) }
    }

    @Test
    fun `독립된 두 문법 오류는 합치지 않고 정상 작업을 고정한 채 실패 작업만 수리한다`() {
        // 준비: 실제 활용 손상 두 곳을 서로 다른 segment에 둔다.
        val plan = CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-free-b3-01",
            "明日の午後に商品を取り置きしていただけるますか。仕事が終わってから受け取りますです。")
        val findings = JsonArray(listOf(
            finding("S1", "いただけるます", "INFLECTION"), finding("S2", "受け取りますです", "SYNTAX"),
        ))
        val verified = JsonObject(CuratedWritingFeedbackV13TestPayload.verified(plan) + (CuratedWritingLanguageFormEvidence.key to findings))
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        assertEquals(2, repairs.tasks.size)
        val first = repairs.tasks[0]
        val second = repairs.tasks[1]
        val draft = CuratedWritingFeedbackV14TestPayload.output(repairs,
            mapOf(first.id to "いただけます", second.id to second.original))

        // 실행: R1은 유효하고 R2는 원문과 같으므로 R1만 고정한다.
        val progress = CuratedWritingFeedbackV14Policy.salvageCore(plan, verified, repairs, draft)
        assertNull(progress.completedResult)
        assertEquals(listOf(second.id), progress.unresolvedTaskIds)
        val frozenFirst = progress.frozenOutput.getValue("repairs").jsonObject.getValue(first.id).jsonObject
        assertEquals("いただけます", frozenFirst.string("replacement"))
        assertEquals(draft.getValue("repairs").jsonObject.getValue(first.id).jsonObject.string("fullPatchedSegment"),
            first.originalSegment.replaceRange(first.start, first.end, frozenFirst.string("replacement")))
        val repeatedFrozen = CuratedWritingFeedbackV14TestPayload.output(repairs,
            mapOf(first.id to "いただけます", second.id to "受け取ります"))
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV14Policy.assembleRepairedCore(plan, verified, repairs, progress, repeatedFrozen)
        }
        val repaired = CuratedWritingFeedbackV14Policy.assembleRepairedCore(plan, verified, repairs, progress,
            CuratedWritingFeedbackV14TestPayload.output(repairs, mapOf(second.id to "受け取ります")))

        // 검증: 실제 오류 두 곳을 보존하며 전체 독립 문법 coverage를 모두 통과한다.
        assertEquals(2, repaired.getValue("necessaryCorrections").jsonArray.size)
        assertEquals(setOf("INFLECTION", "SYNTAX"), repaired.getValue("necessaryCorrections").jsonArray
            .map { it.jsonObject.string("formReason") }.toSet())
    }

    @Test
    fun `선택 제안 실패는 유효 핵심을 유지하고 실패한 핵심을 성공으로 바꾸지 못한다`() {
        // 준비: 핵심 교정을 먼저 전체 검증한다.
        val (plan, verified) = CuratedWritingFeedbackV14TestPayload.homeConflict()
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        val core = CuratedWritingFeedbackV14Policy.assembleCore(plan, verified, repairs,
            CuratedWritingFeedbackV14TestPayload.output(repairs, mapOf("R1" to "完成した植木鉢は家に持ち帰りました。")))

        // 실행: 별도 선택 제안은 실패했지만 이미 검증한 핵심은 같은 형태로 유지한다.
        val result = CuratedWritingFeedbackV14Policy.withOptionalResult(plan, verified, repairs, core,
            JsonArray(emptyList()), "FAILED", "OPTIONAL_VERIFICATION_FAILED")

        // 검증: 필수 교정·판정은 바이트 의미의 JsonElement 그대로이며 status는 별도다.
        assertEquals(core.getValue("necessaryCorrections"), result.getValue("necessaryCorrections"))
        assertEquals(core.getValue("observations"), result.getValue("observations"))
        assertEquals("FAILED", result.string("optionalSuggestionsStatus"))
        for ((status, code) in listOf("SUCCEEDED" to "HIDDEN_FAILURE", "FAILED" to null,
            "NOT_RUN_LIMIT" to null, "NOT_RUN_LIMIT" to "lowercase", "NOT_APPLICABLE" to "HIDDEN_FAILURE")) {
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingFeedbackV14Policy.withOptionalResult(plan, verified, repairs, core,
                    JsonArray(emptyList()), status, code)
            }
        }
        assertEquals("OPTIONAL_DEADLINE_LIMIT", CuratedWritingFeedbackV14Policy.withOptionalResult(plan, verified,
            repairs, core, JsonArray(emptyList()), "NOT_RUN_LIMIT", "OPTIONAL_DEADLINE_LIMIT")
            .string("optionalSuggestionsFailureCode"))
    }

    @Test
    fun `서로 다른 사실 목표가 같은 위치에서 충돌하면 임의 합산하지 않고 명시 차단한다`() {
        // 준비: 테스트 전용으로 동일 원문에 양립할 수 없는 두 저작 목표를 둔다. 공개 과제 expected는 수정하지 않는다.
        val initial = CuratedWritingFeedbackV13TestPayload.workshop("正午に参加しました。")
        val values = mapOf("正午" to "NOON", "午前" to "MORNING", "午後" to "AFTERNOON")
        val facts = listOf(
            CuratedWritingMeaningContract.FactConstraint("first_event_time", "T1", "first_event_time", "MORNING", values, listOf("C1.A1")),
            CuratedWritingMeaningContract.FactConstraint("second_event_time", "T1", "second_event_time", "AFTERNOON", values, listOf("C1.A1")),
        )
        val plan = initial.copy(contract = initial.contract.copy(facts = facts))
        val verified = CuratedWritingFeedbackV13TestPayload.verified(plan)

        // 실행·검증: 동일 authority나 원문 범위만으로 서로 다른 목표를 한 오류로 축약하지 않는다.
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        assertEquals(2, repairs.tasks.size)
        assertTrue(repairs.blockers.any { it.startsWith("OVERLAPPING_DISTINCT_REPAIRS:") })
        assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV14Policy.serverCoreIfComplete(plan, verified, repairs) }
    }

    private fun finding(segment: String, core: String, reason: String) = buildJsonObject {
        put("segmentId", segment); put("quote", core); put("errorCore", core); put("formReason", reason)
    }
}

/** 합성 근거는 수리 계약 검사용이며 실모델 품질 gold가 아니다. DB/HTTP 합성 Provider도 재사용할 수 있다. */
internal object CuratedWritingFeedbackV14TestPayload {
    const val workshopMissing = "先週の土曜日の午後、地域の図書館で友達と参加しました。リサイクル材料で小さな植木鉢を作り、講師は材料の準備方法と作る順番を説明しました。楽しかったので、また参加したいです。"

    fun homeConflict(): Pair<CuratedWritingFeedbackV13Policy.Plan, JsonObject> {
        val plan = CuratedWritingFeedbackV13TestPayload.workshop(workshopMissing.replace("楽しかったので、", "完成した植木鉢は図書館に置いたままです。楽しかったので、"))
        val source = plan.sources.single { it.text.contains("置いたまま") }
        val verified = CuratedWritingFeedbackV13TestPayload.verified(plan)
            .atom("C4.A2", CuratedWritingFeedbackV13TestPayload.edge("CONFLICT", listOf(source.id)))
        return plan to CuratedWritingFeedbackV13Policy.validateVerification(verified, plan)
    }

    fun output(repairs: CuratedWritingRepairPlan.Plan, replacements: Map<String, String>): JsonObject = buildJsonObject {
        put("repairPlanHash", repairs.hash)
        put("repairs", JsonObject(replacements.mapValues { (id, replacement) -> buildJsonObject {
            val task = repairs.task(id)
            put("fullPatchedSegment", task.originalSegment.replaceRange(task.start, task.end, replacement))
            put("learningText", "課題の指定と食い違う箇所、または実際の文法誤りだけを直します。")
        } }))
    }
}

private fun JsonObject.atom(id: String, value: JsonObject): JsonObject =
    JsonObject(this + ("atoms" to JsonObject(getValue("atoms").jsonObject + (id to value))))
private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.observations(): Map<String, JsonObject> = getValue("observations").jsonArray
    .associate { it.jsonObject.string("requirementId") to it.jsonObject }
