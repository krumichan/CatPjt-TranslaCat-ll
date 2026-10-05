package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.application.WritingReviewExecution
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionPort
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Instant
import kotlin.test.*

class WritingReviewObservationTest {
    private val cases = resource("writing-acceptance-python-golden.json").getValue("cases").jsonArray
    private val recovery = resource("writing-source-recovery-python-golden.json")
    private val repair = resource("writing-repair-python-golden.json")
    private val evidence = WritingDraftEvidence("Choose a plan", listOf("A fact"), listOf("An intent"), emptyList(), "Note")

    @Test
    fun `유형별 관측만 추가하고 일반 보정 복구의 기존 Schema를 보존한다`() {
        // 준비: 기존 TRANSLATION Schema를 각 검증 종류의 기준으로 둔다.
        for (variant in listOf("ordinary", "repaired", "recovered")) {
            val revision = if (variant == "repaired") "b".repeat(64) else null
            val recovered = if (variant == "recovered") "c".repeat(64) else null
            val base = WritingReviewSchema.build(evidence, "candidate", "a".repeat(64), revision, recovered)
            for (type in listOf(WritingType.FREE, WritingType.GUIDED)) {
                // 실행: 같은 최종 과제에 유형별 필수 관측을 결합한다.
                val schema = WritingReviewSchema.build(
                    evidence, "candidate", "a".repeat(64), revision, recovered, type,
                )
                val key = observationKey(type)
                val properties = schema.getValue("properties").jsonObject
                val observed = properties.getValue(key).jsonObject
                val observedProperties = observed.getValue("properties").jsonObject

                // 검증: band 앞의 닫힌 필드만 추가하며 기존 required·판정 정의는 그대로다.
                assertEquals(key, properties.keys.first())
                assertEquals(false, observed.getValue("additionalProperties").jsonPrimitive.boolean)
                assertEquals(observedProperties.keys, observed.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
                val idRule = observedProperties.getValue("evidenceSegmentIds").jsonObject
                assertEquals(1, idRule.getValue("minItems").jsonPrimitive.int)
                assertEquals(listOf("O1", "F1", "I1"), idRule.getValue("items").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
                assertEquals(base, JsonObject(schema + mapOf(
                    "properties" to JsonObject(properties - key),
                    "required" to JsonArray(schema.getValue("required").jsonArray.filter { it.jsonPrimitive.content != key }),
                )))
            }
        }
    }

    @Test
    fun `FREE 내용 선택은 기존 PASS 및 TASK_TYPE 거부와 명시적 불확실성으로 판정한다`() {
        // 준비: 합성 원문에서 선택할 내용이 있는 경우와 고정된 메시지인 경우를 구분한다.
        val positive = withObservation(base(), WritingType.FREE)
        val prescribed = withObservation(withTaskStatus(base(), "FAIL", "TASK_TYPE"), WritingType.FREE, "EXPRESSION_ONLY")
        val uncertain = withObservation(withTaskStatus(base(), "UNSURE"), WritingType.FREE, "UNSURE")

        // 실행: 기존 acceptance 함수로 결과를 판단한다.
        fun decision(raw: JsonObject) = WritingReviewAcceptance.decide(
            WritingReviewParser.parse(raw, writingType = WritingType.FREE), 5, WritingType.FREE,
        )

        // 검증: 새 점수나 임계값 없이 기존 거부·재판정 계약을 따른다.
        assertEquals("ACCEPT", decision(positive).action)
        assertEquals(WritingAcceptance("REJECT", "QUALITY_TASK_TYPE"), decision(prescribed))
        assertEquals(WritingAcceptance("ADJUDICATE", "EXPLICIT_UNCERTAINTY"), decision(uncertain))
    }

    @Test
    fun `FREE 관측 상태와 TASK_VALIDITY가 모순되면 성공으로 바꾸지 않는다`() {
        // 준비: 실제 내용 선택이 없거나 불명인데 TASK_VALIDITY PASS를 주장한 응답이다.
        for (status in listOf("EXPRESSION_ONLY", "UNSURE")) {
            val invalid = withObservation(base(), WritingType.FREE, status)

            // 실행 및 검증: 오류 문구만 바꾼 품질 거부로 변환하지 않고 프로토콜 오류로 남긴다.
            assertEquals("CONTENT_CHOICE_STATUS_MISMATCH", assertFailsWith<WritingReviewProtocolException> {
                WritingReviewParser.parse(invalid, writingType = WritingType.FREE)
            }.code)
        }
        val unrelatedFailure = withObservation(
            withTaskStatus(base(), "FAIL", "AMBIGUOUS_TASK"), WritingType.FREE, "EXPRESSION_ONLY",
        )
        assertEquals("CONTENT_CHOICE_STATUS_MISMATCH", assertFailsWith<WritingReviewProtocolException> {
            WritingReviewParser.parse(unrelatedFailure, writingType = WritingType.FREE)
        }.code)
    }

    @Test
    fun `GUIDED 의미 설명은 band를 자동 변경하지 않고 현재 과제 ID에만 결합한다`() {
        // 준비: 내용 관측의 문구로 난이도를 산출하지 않는 계약이다.
        val raw = withObservation(base(), WritingType.GUIDED)

        // 실행: 명시된 의미와 관계를 보존하고 기존 band 판정을 읽는다.
        val parsed = WritingReviewParser.parse(raw, writingType = WritingType.GUIDED)

        // 검증: 실제 필드의 출처만 확인하며 관측 문구로 점수를 바꾸지 않는다.
        assertEquals("Communicate the required request.", parsed.productionObservation?.minimumRequiredMeaning)
        assertEquals("No additional connected relation is required.", parsed.productionObservation?.necessaryRelations)
        assertEquals(5, parsed.estimatedBand)
        assertEquals(null, WritingReviewBinding.failure(parsed, evidence, "synthetic-candidate", "a".repeat(64)))
        assertEquals("ACCEPT", WritingReviewAcceptance.decide(parsed, 5, WritingType.GUIDED).action)
    }

    @Test
    fun `관측 누락 빈 설명 추가 필드 잘못된 타입은 닫힌 계약을 통과하지 못한다`() {
        // 준비: FREE와 GUIDED에 요구된 관측의 경계를 각각 손상시킨다.
        for (type in listOf(WritingType.FREE, WritingType.GUIDED)) {
            val key = observationKey(type)
            val raw = withObservation(base(), type)
            val observed = raw.getValue(key).jsonObject
            val textKey = if (type == WritingType.FREE) "choiceDescription" else "minimumRequiredMeaning"
            val invalids = listOf(
                JsonObject(raw - key),
                JsonObject(raw + (key to JsonObject(observed + (textKey to JsonPrimitive("  "))))),
                JsonObject(raw + (key to JsonObject(observed + ("inventedScore" to JsonPrimitive(90))))),
                JsonObject(raw + (key to JsonObject(observed + ("evidenceSegmentIds" to JsonArray(emptyList()))))),
            )

            // 실행 및 검증: 누락이나 빈 값을 추정하여 채우지 않는다.
            invalids.forEach { assertFailsWith<WritingReviewProtocolException> {
                WritingReviewParser.parse(it, writingType = type)
            } }
            assertFailsWith<WritingReviewProtocolException> { WritingReviewParser.parse(raw) }
        }
    }

    @Test
    fun `관측의 note 이력 숨겨진 원문 없는 ID와 중복 ID를 거부한다`() {
        // 준비: 현재 task에는 O1 F1 I1만 있고 N1은 note다.
        for (type in listOf(WritingType.FREE, WritingType.GUIDED)) {
            val raw = withObservation(base(), type)
            val key = observationKey(type)
            val observed = raw.getValue(key).jsonObject
            for (id in listOf("N1", "R1", "B0", "S0", "F9")) {
                val invalid = JsonObject(raw + (key to JsonObject(observed + ("evidenceSegmentIds" to strings(listOf(id))))))

                // 실행 및 검증: schema를 우회한 Provider 응답도 최종 결합 검증에서 거부한다.
                val parsed = WritingReviewParser.parse(invalid, writingType = type)
                assertEquals("VERIFIER_OBSERVATION_EVIDENCE_INVALID", WritingReviewBinding.failure(
                    parsed, evidence, "synthetic-candidate", "a".repeat(64),
                ))
            }
            val duplicate = JsonObject(raw + (key to JsonObject(observed + ("evidenceSegmentIds" to strings(listOf("O1", "O1"))))))
            assertEquals("EVIDENCE_DUPLICATE", assertFailsWith<WritingReviewProtocolException> {
                WritingReviewParser.parse(duplicate, writingType = type)
            }.code)
        }
    }

    @Test
    fun `실행기는 일반 보정 복구와 재판정 모두 요청 유형의 strict 관측을 요구한다`() = runBlocking {
        // 준비: 제품 실행 경로를 호출하되 모델은 명시적인 합성 계약 응답만 반환한다.
        for (type in listOf(WritingType.FREE, WritingType.GUIDED)) {
            for (variant in listOf("ordinary", "repaired", "recovered", "adjudicated")) {
                val request = JsonObject(repair.getValue("request").jsonObject + ("writingType" to JsonPrimitive(type.name)))
                val original = WritingCandidatePolicy.parse(repair.getValue("original").jsonObject)
                val revised = WritingCandidatePolicy.parse(repair.getValue("revised").jsonObject)
                val plan = if (variant == "repaired") WritingDifficultyRepairPlan(
                    original, WritingCandidatePolicy.contentHash(request, original), 1, type,
                    listOf(WritingRepairObservation("SCOPE_INTERACTION", listOf("O1"))),
                    listOf(WritingRepairObservation("SCOPE_TOO_ROUTINE", listOf("O1"))),
                ) else null
                val source = if (variant == "recovered") WritingSourceRecovery.Evidence(original.originText, "d".repeat(64)) else null
                var calls = 0
                val execution = WritingReviewExecution(ModelExecutionPort { command ->
                    calls++
                    assertEquals(true, command.strict)
                    val properties = command.responseSchema!!.getValue("properties").jsonObject
                    assertEquals(observationKey(type), properties.keys.first())
                    var output = when (variant) {
                        "repaired" -> base("repair-pass")
                        "recovered" -> recovery.getValue("reviewOutput").jsonObject
                        else -> base()
                    }
                    for (field in listOf("candidateId", "contentHash", "revisionHash", "recoveryHash")) {
                        properties[field]?.jsonObject?.get("enum")?.jsonArray?.single()?.let {
                            output = JsonObject(output + (field to it))
                        }
                    }
                    ModelExecutionResult(withObservation(output, type), 2, 2, "synthetic", "synthetic")
                })

                // 실행: 일반과 수정본, 복구본, 독립 재판정 모두 같은 타입 인자를 전달해야 한다.
                val result = execution.assess(
                    request, revised, "typed-$variant", Instant.now().plusSeconds(30), plan,
                    adjudicator = variant == "adjudicated", sourceRecovery = source,
                )

                // 검증: type별 관측이 파싱되어 돌아오며 추가 호출은 없다.
                assertEquals(1, calls)
                if (type == WritingType.FREE) assertNotNull(result.review.contentChoice)
                else assertNotNull(result.review.productionObservation)
            }
        }
    }

    @Test
    fun `누락 관측은 실행기의 기존 재시도 한도 뒤 실패한다`() = runBlocking {
        // 준비: 이전 FREE 출력처럼 관측 필드만 없는 합성 응답이다.
        val request = repair.getValue("request").jsonObject
        val draft = WritingCandidatePolicy.parse(repair.getValue("revised").jsonObject)
        for (adjudicator in listOf(false, true)) {
            var calls = 0
            val execution = WritingReviewExecution(ModelExecutionPort {
                calls++
                ModelExecutionResult(base(), 2, 2, "synthetic", "synthetic")
            })

            // 실행 및 검증: 일반 2회·재판정 1회 정책을 늘리지 않는다.
            assertEquals("SCHEMA_FIELDS_INVALID", assertFailsWith<WritingReviewProtocolException> {
                execution.assess(request, draft, "missing-observation", Instant.now().plusSeconds(30), adjudicator = adjudicator)
            }.code)
            assertEquals(if (adjudicator) 1 else 2, calls)
        }
    }

    private fun base(name: String? = null): JsonObject = (if (name == null) cases.first() else
        cases.first { it.jsonObject.getValue("name").jsonPrimitive.content == name })
        .jsonObject.getValue("review").jsonObject

    private fun withObservation(raw: JsonObject, type: WritingType, status: String = "SUBSTANTIVE_CHOICE"): JsonObject {
        val observation = buildJsonObject {
            if (type == WritingType.FREE) {
                put("status", status)
                put("choiceDescription", when (status) {
                    "EXPRESSION_ONLY" -> "The message already fixes the action and reason."
                    "UNSURE" -> "The task does not establish whether the learner chooses a plan."
                    else -> "The learner must choose a plan."
                })
            } else {
                put("minimumRequiredMeaning", "Communicate the required request.")
                put("necessaryRelations", "No additional connected relation is required.")
            }
            put("evidenceSegmentIds", strings(listOf("O1")))
        }
        return JsonObject(raw + mapOf("observedWritingType" to JsonPrimitive(type.name), observationKey(type) to observation))
    }

    private fun withTaskStatus(raw: JsonObject, status: String, issue: String? = null): JsonObject = JsonObject(raw + mapOf(
        "checks" to JsonArray(raw.getValue("checks").jsonArray.map {
            val check = it.jsonObject
            if (check.getValue("criterion").jsonPrimitive.content == "TASK_VALIDITY")
                JsonObject(check + ("status" to JsonPrimitive(status))) else check
        }),
        "issues" to strings(listOfNotNull(issue)),
        "verdict" to JsonPrimitive(if (status == "FAIL") "REJECT" else "UNSURE"),
    ))

    private fun observationKey(type: WritingType) = if (type == WritingType.FREE) "contentChoiceObservation" else "productionObservation"
    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun resource(name: String) = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/contracts/$name")).readText()).jsonObject
}
