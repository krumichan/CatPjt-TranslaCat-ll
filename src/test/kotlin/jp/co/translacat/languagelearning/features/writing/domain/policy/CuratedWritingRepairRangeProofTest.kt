package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingRepairRangeProofTest {
    @Test
    fun `연속된 두 귀가 충돌 절은 실제 인용을 보존한 단일 수리로 전체 검증한다`() {
        // 준비: 고정 새 답안 N01의 두 절은 하나의 귀가 사실을 연속해서 부정한다.
        val (plan, verified) = contiguousHome()
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        val task = repairs.tasks.single()
        assertTrue(repairs.blockers.isEmpty())
        assertNotNull(task.rangeProof)
        assertEquals("できあがった鉢は図書館の展示棚に置き、何も持たずに帰りました。", task.original)
        assertEquals(setOf("T4", "P2"), task.coverage.map { it.authorityId }.toSet())
        val reply = CuratedWritingFeedbackV14TestPayload.output(repairs,
            mapOf(task.id to "できあがった鉢は家に持って帰りました。"))

        // 실행: joined quote 없이 원래 두 source를 근거로 한 typed range만 사용한다.
        val core = CuratedWritingFeedbackV14Policy.assembleCore(plan, verified, repairs, reply)
        val rechecked = CuratedWritingFeedbackV14Policy.validateCoreResult(plan, verified, core)
        val optionalFailed = CuratedWritingFeedbackV14Policy.withOptionalResult(plan, verified, repairs, core,
            JsonArray(emptyList()), "FAILED", "OPTIONAL_VERIFICATION_FAILED")

        // 검증: 구형 경로의 containment는 여전히 거절하며 새 경로도 원래 충돌 판정을 유지한다.
        assertEquals(core, rechecked)
        assertEquals(core.getValue("necessaryCorrections"), optionalFailed.getValue("necessaryCorrections"))
        assertEquals(1, core.getValue("necessaryCorrections").jsonArray.size)
        val c4 = core.getValue("observations").jsonArray.single { it.jsonObject.string("requirementId") == "C4" }.jsonObject
        assertEquals("UNMET", c4.string("status"))
        assertEquals(listOf("PRESENT", "CONFLICT"), c4.getValue("requirementParts").jsonArray.map { it.jsonObject.string("status") })
        assertEquals(2, c4.getValue("requirementParts").jsonArray[1].jsonObject.getValue("evidence").jsonArray.size)
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV13Policy.assemble(rawContent(core), plan, verified)
        }
    }

    @Test
    fun `범위 증명은 다른 revision 권한 및 문법 오류의 우회 권한이 아니다`() {
        // 준비: 실제 proof와 그 proof로 검사할 정상 raw 행을 만든다.
        val (plan, verified) = contiguousHome()
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        val task = repairs.tasks.single()
        val proof = assertNotNull(task.rangeProof)
        val core = CuratedWritingFeedbackV14Policy.assembleCore(plan, verified, repairs,
            CuratedWritingFeedbackV14TestPayload.output(repairs, mapOf(task.id to "できあがった鉢は家に持って帰りました。")))
        val raw = rawContent(core)
        val correction = raw.getValue("necessaryCorrections").jsonArray.single().jsonObject
        val wrongAuthority = JsonObject(correction + ("sourceFactId" to JsonPrimitive("T3")))
        val languageForm = JsonObject(correction + mapOf("issueType" to JsonPrimitive("LANGUAGE_FORM"),
            "sourceFactId" to JsonPrimitive(""), "formReason" to JsonPrimitive("SYNTAX")))

        // 실행·검증: proof는 source/context/verified 전체에 묶이며 다른 유형의 인용 검사도 유지된다.
        assertFailsWith<IllegalArgumentException> { proof.requireBound(plan,
            verified.atom("C4.A2", CuratedWritingFeedbackV13TestPayload.edge("ABSENT", emptyList()))) }
        assertFailsWith<IllegalArgumentException> { proof.requireContext(plan.context, plan.requirements,
            plan.segments.map { it.copy(text = it.text + "。") }) }
        for (row in listOf(wrongAuthority, languageForm)) {
            val content = JsonObject(raw + ("necessaryCorrections" to JsonArray(listOf(row))))
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingFeedbackV11Policy.validateWithRepairRanges(
                    JsonObject(CuratedWritingFeedbackV13Policy.frozenDecisions(plan, verified) + content),
                    plan.context, plan.requirements, plan.segments, listOf(proof))
            }
        }
    }

    @Test
    fun `다른 사건과 공유하거나 중간 절을 건너뛰는 source는 합치지 않는다`() {
        // 준비: 같은 source를 다른 atom도 사용하거나, 두 source 사이에 별도 절을 둔다.
        val (plan, verified) = contiguousHome()
        val selected = plan.sources.filter { it.segmentId == "S3" }.map { it.id }
        val shared = verified.atom("C3.A1", CuratedWritingFeedbackV13TestPayload.edge("SUPPORT", selected))
        val (gappedPlan, gappedVerified) = contiguousHome("できあがった鉢は図書館の展示棚に置き、講師にお礼を伝え、何も持たずに帰りました。")
        val gappedSources = gappedPlan.sources.filter { it.segmentId == "S3" }.let { listOf(it.first().id, it.last().id) }
        val gap = gappedVerified.atom("C4.A2", CuratedWritingFeedbackV13TestPayload.edge("CONFLICT", gappedSources))

        // 실행·검증: 단순히 같은 atom이라는 이유로 중간 사건까지 수정 범위를 확대하지 않는다.
        assertTrue(CuratedWritingFeedbackV14Policy.planRepairs(plan, shared).blockers
            .contains("AMBIGUOUS_NATURAL_CONFLICT_LOCATION:C4.A2"))
        assertTrue(CuratedWritingFeedbackV14Policy.planRepairs(gappedPlan, gap).blockers
            .contains("AMBIGUOUS_NATURAL_CONFLICT_LOCATION:C4.A2"))
    }

    @Test
    fun `전체 segment 출력은 지정된 오류 밖의 앞뒤 문자를 바꾸지 못한다`() {
        // 준비: 문장 중간의 문법 오류 하나만 고치도록 범위를 고정한다.
        val plan = CuratedWritingFeedbackV13TestPayload.catalog("ko-ja-free-b3-01",
            "明日の午後に商品を取り置きしていただけるますか。仕事が終わってから受け取りに行きます。")
        val verified = JsonObject(CuratedWritingFeedbackV13TestPayload.verified(plan) +
            (CuratedWritingLanguageFormEvidence.key to JsonArray(listOf(buildJsonObject {
                put("segmentId", "S1"); put("quote", "いただけるます"); put("errorCore", "いただけるます"); put("formReason", "INFLECTION")
            }))))
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        val task = repairs.tasks.single()
        val correct = CuratedWritingFeedbackV14TestPayload.output(repairs, mapOf(task.id to "いただけます"))
        val correctRow = correct.getValue("repairs").jsonObject.getValue(task.id).jsonObject
        val changedPrefix = JsonObject(correctRow + ("fullPatchedSegment" to
            JsonPrimitive(correctRow.string("fullPatchedSegment").replace("明日", "来週"))))
        val oldProtocol = buildJsonObject { put("replacement", "いただけます"); put("learningText", correctRow.getValue("learningText")) }

        // 실행·검증: 정상 전체 문장은 통과하고, 범위 밖 수정과 과거 필드 shape는 거절한다.
        assertNotNull(CuratedWritingFeedbackV14Policy.assembleCore(plan, verified, repairs, correct))
        for (invalid in listOf(changedPrefix, oldProtocol)) {
            val reply = JsonObject(correct + ("repairs" to buildJsonObject { put(task.id, invalid) }))
            assertFailsWith<IllegalArgumentException> { CuratedWritingFeedbackV14Policy.assembleCore(plan, verified, repairs, reply) }
        }
    }

    @Test
    fun `재검증은 저장 핵심의 원래 관찰과 권한 및 hash 변경을 거절한다`() {
        // 준비: 검증된 핵심 결과와 독립적으로 훼손한 public DTO를 만든다.
        val (plan, verified) = contiguousHome()
        val repairs = CuratedWritingFeedbackV14Policy.planRepairs(plan, verified)
        val core = CuratedWritingFeedbackV14Policy.assembleCore(plan, verified, repairs,
            CuratedWritingFeedbackV14TestPayload.output(repairs, mapOf("R1" to "できあがった鉢は家に持って帰りました。")))
        val observations = core.getValue("observations").jsonArray.mapIndexed { index, raw ->
            if (index == 3) JsonObject(raw.jsonObject + ("status" to JsonPrimitive("MET"))) else raw
        }
        val invalid = listOf(JsonObject(core + ("observations" to JsonArray(observations))),
            JsonObject(core + ("repairPlanHash" to JsonPrimitive("0".repeat(64)))))

        // 실행·검증: 결과가 성공 DTO라는 사실만으로 변경된 판정을 신뢰하지 않는다.
        invalid.forEach { assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV14Policy.validateCoreResult(plan, verified, it)
        } }
    }

    private fun contiguousHome(home: String = "できあがった鉢は図書館の展示棚に置き、何も持たずに帰りました。"):
        Pair<CuratedWritingFeedbackV13Policy.Plan, JsonObject> {
        val plan = CuratedWritingFeedbackV13TestPayload.workshop(
            "先週の土曜日の午後、地域の図書館の工芸教室に友達と参加しました。再利用の材料で小さな植木鉢を作り、講師から材料の用意の仕方と作業の手順を教わりました。" +
                home + "楽しい体験だったので、また参加したいです。")
        val initial = CuratedWritingFeedbackV13TestPayload.verified(plan)
        val atoms = initial.getValue("atoms").jsonObject.mapValues { (id, value) ->
            val segment = when (id) { "C1.A1", "C4.A1" -> "S1"; "C2.A1", "C3.A1" -> "S2"; "C4.A2" -> "S3"; else -> "S4" }
            val edge = CuratedWritingFeedbackV13TestPayload.edge(if (id == "C4.A2") "CONFLICT" else "SUPPORT",
                plan.sources.filter { it.segmentId == segment }.map { it.id })
            if (value.jsonObject.containsKey("alternatives")) buildJsonObject {
                put("alternatives", buildJsonObject { value.jsonObject.getValue("alternatives").jsonObject.keys.forEach { put(it, edge) } })
            } else edge
        }
        val verified = JsonObject(initial + ("atoms" to JsonObject(atoms)))
        return plan to CuratedWritingFeedbackV13Policy.validateVerification(verified, plan)
    }

    private fun rawContent(core: JsonObject): JsonObject = buildJsonObject {
        put("necessaryCorrections", JsonArray(core.getValue("necessaryCorrections").jsonArray.map { raw ->
            JsonObject(raw.jsonObject.filterKeys { it in setOf("requirementId", "segmentId", "patch", "issueType", "sourceFactId", "sourceAuthorityId", "formReason", "learningText") })
        }))
        put("optionalAlternatives", JsonArray(emptyList())); put("uncertainties", JsonArray(emptyList()))
    }
}

private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.atom(id: String, value: JsonObject): JsonObject =
    JsonObject(this + ("atoms" to JsonObject(getValue("atoms").jsonObject + (id to value))))
