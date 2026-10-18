package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingCoreVerificationV14Test {
    @Test
    fun authorizedFactRepairPassesWithOriginalConflictAndFullContextPreserved() {
        // 준비: 과제의 도서관→집 변경은 허가된 교정이며 원문 C4는 계속 UNMET다.
        val check = home()

        // 실행: 생성자와 별도의 전체 문맥 검사 결과를 검증한다.
        val prompt = Json.parseToJsonElement(CuratedWritingCoreVerificationV14.prompt(check)).jsonObject
        CuratedWritingCoreVerificationV14.validate(accepted(check), check)

        // 검증: 같은 T/P 사건은 한 R이고, 허가된 사실 변경을 새 사실 발명으로 거절하지 않는다.
        assertEquals(1, prompt.getValue("repairTasks").jsonArray.size)
        assertTrue(prompt.getValue("fullPatchedContext").jsonPrimitive.content.contains("家に持って帰りました"))
        assertEquals("UNMET", check.core.getValue("observations").jsonArray.single {
            it.jsonObject.getValue("requirementId").jsonPrimitive.content == "C4"
        }.jsonObject.getValue("status").jsonPrimitive.content)
        assertTrue(CuratedWritingCoreVerificationV14.schema(check).toString().contains("noUnlicensedProposition"))
        assertFalse(CuratedWritingCoreVerificationV14.schema(check).toString().contains("noNewProposition\""))
        assertEquals(check.core, prompt.getValue("strictCoreResult"))
        assertEquals(check.coreHash, CuratedWritingRepairPlan.digest(buildJsonObject {
            put("sourceRevisionHash", prompt.getValue("sourceRevisionHash"))
            put("repairPlanHash", prompt.getValue("repairPlanHash"))
            put("core", prompt.getValue("strictCoreResult"))
        }.toString()))
    }

    @Test
    fun anInventedCompanionFailsIndependentCoreVerification() {
        // 준비: 귀가 수리 권한은 새 동반자를 발명할 권한이 아니다.
        val check = home("完成した植木鉢は妹と家に持って帰りました。")
        assertTrue(check.patchedSegments.values.joinToString("").contains("妹と"))

        // 실행·검증: 이 실패는 optional 상태로 반환되지 않고 필수 core 예외로 전파된다.
        val error = assertFailsWith<IllegalArgumentException> {
            CuratedWritingCoreVerificationV14.validate(accepted(check, taskFailure = "noUnlicensedProposition"), check)
        }
        assertEquals("WRITING_FEEDBACK_CORE_VERIFICATION_REJECTED", error.message)
    }

    @Test
    fun actualPartialInflectionRangeCannotHideMalformedWholeSentenceInOptionalFailure() {
        // 준비: actual N04의 るまし→りました는 원래 마지막 た를 남긴다.
        val answer = "先週末、兄と川沿いを散歩しました。途中の公園で少し休みました。特に、水面に夕焼けが映った景色が印象に残るました。ゆっくり話しながら歩けたので、よい気分転換になりました。次の週末は、同じ兄と美術館に行ってみたいです。"
        val plan = weekend(answer)
        val base = CuratedWritingFeedbackV13TestPayload.verified(plan)
        val verified = JsonObject(base + (CuratedWritingLanguageFormEvidence.key to JsonArray(listOf(buildJsonObject {
            put("segmentId", "S3"); put("quote", "残るました"); put("errorCore", "るまし"); put("formReason", "INFLECTION")
        }))))
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        val core = CuratedWritingFeedbackV14Policy.assembleCore(plan, verified, repairs,
            CuratedWritingFeedbackV14TestPayload.output(repairs, mapOf("R1" to "りました")))
        val check = CuratedWritingCoreVerificationV14.prepare(plan, verified, repairs, core)

        // 실행: 독립 검사는 local 문자열이 아닌 전체 필수 적용 결과의 문법을 판정한다.
        val prompt = Json.parseToJsonElement(CuratedWritingCoreVerificationV14.prompt(check)).jsonObject
        assertTrue(prompt.getValue("fullPatchedContext").jsonPrimitive.content.contains("残りましたた。"))
        val error = assertFailsWith<IllegalArgumentException> {
            CuratedWritingCoreVerificationV14.validate(accepted(check, contextFailure = "grammarValid"), check)
        }

        // 검증: 필수 수리 실패를 optional 실패로 바꾼 정상 응답은 만들어지지 않는다.
        assertEquals("WRITING_FEEDBACK_CORE_VERIFICATION_REJECTED", error.message)
        assertTrue(core.getValue("optionalAlternatives").jsonArray.isEmpty())
    }

    @Test
    fun everyTaskAndWholeContextFailureBlocksCore() {
        // 준비: 사실 보존/조건/문법/설명은 각각 필요한 조건이다.
        val check = home()

        // 실행·검증: 어느 검사도 생략하거나 false를 성공으로 바꾸지 않는다.
        taskChecks.forEach { failed ->
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingCoreVerificationV14.validate(accepted(check, taskFailure = failed), check)
            }
        }
        contextChecks.forEach { failed ->
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingCoreVerificationV14.validate(accepted(check, contextFailure = failed), check)
            }
        }
    }

    @Test
    fun rejectsWrongIdentityMissingTasksStringBooleansAndForgedContext() {
        // 준비: checker의 결과는 고정 core/원문/수리 계획에만 적용할 수 있다.
        val check = home()
        val valid = accepted(check)
        val task = valid.getValue("tasks").jsonObject.getValue("R1").jsonObject
        val malformed = listOf(
            JsonObject(valid + ("sourceRevisionHash" to JsonPrimitive("stale"))),
            JsonObject(valid + ("repairPlanHash" to JsonPrimitive("0".repeat(64)))),
            JsonObject(valid + ("coreHash" to JsonPrimitive("0".repeat(64)))),
            JsonObject(valid + ("tasks" to JsonObject(emptyMap()))),
            JsonObject(valid + ("tasks" to JsonObject(mapOf("R2" to task)))),
            JsonObject(valid + ("tasks" to JsonObject(mapOf("R1" to JsonObject(task + ("grammarValid" to JsonPrimitive("true"))))))),
            JsonObject(valid + ("wholeContext" to JsonObject(valid.getValue("wholeContext").jsonObject + ("reason" to JsonPrimitive(" "))))),
        )

        // 실행·검증: 다른 revision·누락·타입 오류와 caller의 문맥 변조 모두 거절한다.
        malformed.forEach { output -> assertFailsWith<IllegalArgumentException> { CuratedWritingCoreVerificationV14.validate(output, check) } }
        val forged = check.copy(patchedSegments = check.patchedSegments + ("S1" to "友達を発明しました。"))
        assertFailsWith<IllegalArgumentException> { CuratedWritingCoreVerificationV14.validate(valid, forged) }
    }

    @Test
    fun noMandatoryRepairDoesNotCreateAVerificationCall() {
        // 준비: 귀가는 누락되었지만 교정할 실제 충돌이나 LF는 없다.
        val plan = CuratedWritingFeedbackV13TestPayload.workshop(CuratedWritingFeedbackV14TestPayload.workshopMissing)
        val base = CuratedWritingFeedbackV13TestPayload.verified(plan)
        val verified = JsonObject(base + ("atoms" to JsonObject(base.getValue("atoms").jsonObject +
            ("C4.A2" to CuratedWritingFeedbackV13TestPayload.edge("ABSENT", emptyList())))))
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        val core = assertNotNull(CuratedWritingFeedbackV14Policy.serverCoreIfComplete(plan, verified, repairs))

        // 실행·검증: 단순 누락을 검사나 생성 호출을 위한 필수 수리로 만들지 않는다.
        assertTrue(core.getValue("necessaryCorrections").jsonArray.isEmpty())
        assertFailsWith<IllegalArgumentException> { CuratedWritingCoreVerificationV14.prepare(plan, verified, repairs, core) }
    }

    private fun home(replacement: String = "完成した植木鉢は家に持って帰りました。"): CuratedWritingCoreVerificationV14.CheckPlan {
        val (plan, verified) = CuratedWritingFeedbackV14TestPayload.homeConflict()
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        val core = CuratedWritingFeedbackV14Policy.assembleCore(plan, verified, repairs,
            CuratedWritingFeedbackV14TestPayload.output(repairs, mapOf("R1" to replacement)))
        return CuratedWritingCoreVerificationV14.prepare(plan, verified, repairs, core)
    }

    private fun accepted(check: CuratedWritingCoreVerificationV14.CheckPlan, taskFailure: String? = null,
                         contextFailure: String? = null): JsonObject = buildJsonObject {
        put("sourceRevisionHash", check.sourceRevisionHash); put("repairPlanHash", check.repairs.hash); put("coreHash", check.coreHash)
        put("tasks", buildJsonObject { check.repairs.tasks.forEach { task -> put(task.id, buildJsonObject {
            taskChecks.forEach { put(it, it != taskFailure) }; put("reason", "The fixed authorized repair is complete without other changes.")
        }) } })
        put("wholeContext", buildJsonObject {
            contextChecks.forEach { put(it, it != contextFailure) }; put("reason", "The whole resulting context is grammatical and preserves unaffected facts.")
        })
    }
    private fun weekend(answer: String) = CuratedWritingFeedbackV13TestPayload.prepare(WritingType.FREE,
        "지난 주말에 한 활동을 자유롭게 정해 일본어로 한 문단을 쓰세요. 그 활동에서 무엇을 했는지, 누구와 함께했는지, 가장 인상 깊었던 점이 무엇인지, 그리고 다음 주말에 하고 싶은 일을 연결해서 표현하세요.",
        listOf("주말에 한 활동을 구체적으로 제시할 것", "누구와 함께했는지 밝힐 것", "가장 인상 깊었던 점을 설명할 것",
            "다음 주말에 하고 싶은 일을 표현할 것", "내용을 연결된 한 문단으로 작성할 것"), answer)
    private val taskChecks = listOf("targetRepaired", "grammarValid", "unaffectedMeaningPreserved", "noUnlicensedProposition", "explanationAccurate")
    private val contextChecks = listOf("grammarValid", "unaffectedFactsPreserved", "conditionsPreserved", "noUnlicensedProposition")
}
