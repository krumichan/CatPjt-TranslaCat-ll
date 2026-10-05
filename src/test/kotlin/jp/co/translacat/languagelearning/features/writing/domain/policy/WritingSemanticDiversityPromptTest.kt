package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.application.*
import jp.co.translacat.languagelearning.features.writing.domain.model.*
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import kotlin.test.*

/** 실모델 실패 원문을 보존한 문맥/판정 계약 검사다. 모델의 의미 분류 품질 통과로 해석하지 않는다. */
class WritingSemanticDiversityPromptTest {
    private val previous = "모임에 사용할 컵과 접시는 준비했지만, 시간이 부족해서 테이블보는 내일 사려고 해요."
    private val repeated = "오늘은 시간이 부족해서 테이블보는 준비하지 못하지만, 내일 모임에 가기 전에 사 놓을게요."
    private val distinct = "이번 동아리 모임 준비를 위해 종이컵을 가져와 주세요. 참가자가 많아서 필요해요."

    @Test
    fun `실제 의미 중복 원문은 분류 label이 바뀌어도 R 비교 문맥과 기존 거부 계약에 남는다`() {
        // 준비: 실제 baseline-translation-b3의 3/4번 문항과 서로 다른 generator metadata다.
        val request = request(WritingType.TRANSLATION, listOf(history(previous)))
        val draft = draft(repeated, "EXPLAIN_REASON", "준비 상황 설명")
        val old = draft(previous, "REPORT", "준비 상황 알림")

        // 실행: 표면 유사도는 이 사례의 의미 판정기가 아님을 보존하고 독립 검토 문맥을 확인한다.
        val deterministic = WritingDiversityValidator().validate(repeated, WritingCandidatePolicy.metadata(draft.metadata),
            listOf(previous to WritingCandidatePolicy.metadata(old.metadata)))
        val prompt = WritingReviewPrompt.build(request, draft, "synthetic-candidate", "a".repeat(64))
        val body = reviewPayload(prompt)

        // 검증: 현재 threshold 변경 없이 이전 원문을 온전히 전달하고 기존 거부 의미를 요구한다.
        assertTrue(deterministic.accepted)
        assertEquals(.3530939318018998, WritingDiversityValidator.cosine(previous, repeated), .000000000001)
        val retained = body.getValue("diversityAudit").jsonObject.getValue("retainedCurrentItems").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("R1"), retained["id"])
        assertEquals(JsonPrimitive(previous), retained["content"])
        assertTrue(prompt.contains(WritingDiversityPromptContract.review))
        assertFalse("targetBand" in body)
        assertEquals("REJECT", decision(rejectedReview()).action)
        assertEquals("QUALITY_DIVERSITY_SCENE_REPETITION", decision(rejectedReview()).reason)
    }

    @Test
    fun `같은 topic이지만 부탁과 준비 계획이 다른 정상 문항은 기존 PASS 계약을 유지한다`() {
        // 준비: 실제 같은 세트의 다른 목적 문항을 비교하되 topic의 반복 자체를 실패로 만들지 않는다.
        val request = request(WritingType.TRANSLATION, listOf(history(previous)))
        val candidate = draft(distinct, "REQUEST", "준비물 부탁")

        // 실행
        val generation = WritingGenerationPrompt.build(request, WritingType.TRANSLATION, 3)
        val review = WritingReviewPrompt.build(request, candidate, "synthetic-candidate", "a".repeat(64))
        val accepted = decision(baseReview())

        // 검증: 정상 반복과 실제 과제 중복의 차이를 명시하고 판정 threshold/rubric은 그대로 둔다.
        assertTrue(generation.contains(WritingDiversityPromptContract.generation))
        assertTrue(review.contains("Sharing a topic or grammar pattern alone is valid"))
        assertEquals("ACCEPT", accepted.action)
        assertEquals(JsonPrimitive(previous), generationPayload(generation)
            .getValue("retainedCurrentWritingTasks").jsonArray.single().jsonObject["content"])
    }

    @Test
    fun `worker의 현재 게시 문항은 같은 이력을 보완하고 GUIDED 안내와 FREE 빈 안내를 보존한다`() {
        // 준비: 같은 원문을 가진 옛 Writing fingerprint와 다른 기능 이력이 섞인 요청이다.
        for (type in listOf(WritingType.GUIDED, WritingType.FREE)) {
            val facts = if (type == WritingType.GUIDED) listOf("예약은 금요일입니다.") else emptyList()
            val intents = if (type == WritingType.GUIDED) listOf("예약 변경을 부탁하세요.") else emptyList()
            val limits = if (type == WritingType.GUIDED) listOf("새로운 날짜를 임의로 확정하지 마세요.") else emptyList()
            val item = WritingItem(1, 1, 41, 1, WritingDifficulty.NORMAL, "현재 표시 과제", "[]", "[]", "표현을 연습하세요.",
                strings(facts).toString(), strings(intents).toString(), strings(limits).toString(), 3,
                WritingCandidatePolicy.metadata(draft("현재 표시 과제", "REQUEST", "일정 문의").metadata).toString())
            val original = request(type, listOf(history(item.originText), JsonObject(history(item.originText) +
                ("sourceType" to JsonPrimitive("LISTENING")))))

            // 실행: 실제 worker의 문맥 조립을 호출하되 DB나 provider 실행은 허용하지 않는다.
            val scoped = workerHistory(original, listOf(item))
            val generation = generationPayload(WritingGenerationPrompt.build(scoped, type, 3))
            val review = reviewPayload(WritingReviewPrompt.build(scoped, draft("새 과제", "REQUEST", "문의"),
                "synthetic-candidate", "a".repeat(64)))

            // 검증: 같은 원문 중복 추가 없이 실제 안내 세 필드가 생성/검토 모두에 전달된다.
            val generated = generation.getValue("retainedCurrentWritingTasks").jsonArray
            val reviewed = review.getValue("diversityAudit").jsonObject.getValue("retainedCurrentItems").jsonArray
            assertEquals(1, generated.size)
            assertEquals(generated, reviewed)
            assertEquals(strings(facts), generated.single().jsonObject["providedFacts"])
            assertEquals(strings(intents), generated.single().jsonObject["requiredIntents"])
            assertEquals(strings(limits), generated.single().jsonObject["responseConstraints"])
            assertFalse("providedFacts" in original.getValue("diversityContext").jsonObject
                .getValue("currentSession").jsonArray.first().jsonObject)
        }
    }

    @Test
    fun `원문 복구의 축소 문맥은 전체 이력 재노출로 바뀌지 않고 일반 비교 window는 네 문항이다`() {
        // 준비: 기존 window를 넘는 이력과 별도 기능 데이터를 구성한다.
        val request = request(WritingType.TRANSLATION, (1..6).map { history("과거 문항 $it") })

        // 실행
        val normal = generationPayload(WritingGenerationPrompt.build(request, WritingType.TRANSLATION, 3))
        val recovery = generationPayload(WritingGenerationPrompt.build(request, WritingType.TRANSLATION, 3,
            sourceRecoveryMode = true))

        // 검증: 원래 축소 복구 경계와 최근 4개 정책을 지키며 누락된 안내는 새로 만들지 않는다.
        assertEquals(listOf("R1", "R2", "R3", "R4"), normal.getValue("retainedCurrentWritingTasks").jsonArray
            .map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(JsonPrimitive("과거 문항 3"), normal.getValue("retainedCurrentWritingTasks").jsonArray.first().jsonObject["content"])
        assertEquals(JsonNull, normal.getValue("retainedCurrentWritingTasks").jsonArray.first().jsonObject["providedFacts"])
        assertFalse("retainedCurrentWritingTasks" in recovery)
        assertFalse(recovery.toString().contains("과거 문항"))
    }

    private fun request(type: WritingType, history: List<JsonObject>): JsonObject {
        val base = Json.parseToJsonElement(checkNotNull(javaClass.getResource(
            "/contracts/writing-candidate-policy-python-golden.json",
        )).readText()).jsonArray.first().jsonObject.getValue("request").jsonObject
        return JsonObject(base + mapOf("writingType" to JsonPrimitive(type.name),
            "diversityContext" to buildJsonObject { put("currentSession", JsonArray(history)) }))
    }

    private fun history(content: String) = buildJsonObject {
        put("sourceType", "WRITING"); put("content", content)
        put("semanticSummary", "Generator의 분류 설명")
    }

    private fun draft(content: String, intent: String, archetype: String) = WritingCandidateDraft(
        content, emptyList(), listOf("MEANING"), "상황에 맞게 표현하세요.", emptyList(), emptyList(), emptyList(),
        WritingCandidateMetadata("HOBBY", intent, archetype, emptyList(), emptyList(), "Generator 분류", false),
    )

    private fun workerHistory(request: JsonObject, accepted: List<WritingItem>): JsonObject {
        val work = Proxy.newProxyInstance(WritingSetUnitOfWork::class.java.classLoader,
            arrayOf(WritingSetUnitOfWork::class.java)) { _, method, _ -> error("Unexpected work call: ${method.name}") }
            as WritingSetUnitOfWork
        val model = ModelExecutionPort { error("No provider calls allowed") }
        val worker = WritingGenerationWorker(WritingGenerationState(work), work,
            WritingGenerationExecution(model), WritingReviewExecution(model))
        val method = WritingGenerationWorker::class.java.getDeclaredMethod("withAcceptedHistory", JsonObject::class.java, List::class.java)
        method.isAccessible = true
        return method.invoke(worker, request, accepted) as JsonObject
    }

    private fun baseReview(): JsonObject {
        val original = Json.parseToJsonElement(checkNotNull(javaClass.getResource(
            "/contracts/writing-acceptance-python-golden.json",
        )).readText()).jsonObject.getValue("cases").jsonArray.first().jsonObject.getValue("review").jsonObject
        return JsonObject(original + mapOf("observedWritingType" to JsonPrimitive("TRANSLATION"), "estimatedBand" to JsonPrimitive(3)))
    }

    private fun rejectedReview(): JsonObject {
        val original = baseReview()
        return JsonObject(original + mapOf("verdict" to JsonPrimitive("REJECT"),
            "issues" to strings(listOf("DIVERSITY_SCENE_REPETITION")),
            "checks" to JsonArray(original.getValue("checks").jsonArray.map {
                if (it.jsonObject["criterion"] == JsonPrimitive("TASK_VALIDITY"))
                    JsonObject(it.jsonObject + ("status" to JsonPrimitive("FAIL"))) else it
            })))
    }

    private fun decision(raw: JsonObject) = WritingReviewAcceptance.decide(WritingReviewParser.parse(raw), 3, WritingType.TRANSLATION)
    private fun generationPayload(prompt: String) = Json.parseToJsonElement(prompt.substringAfter("<learning-data>\n")
        .substringBefore("\n</learning-data>")).jsonObject
    private fun reviewPayload(prompt: String) = Json.parseToJsonElement(prompt.substringAfter("<writing-review-data>\n")
        .substringBefore("\n</writing-review-data>")).jsonObject
    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
}
