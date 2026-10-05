package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WritingReviewDemandSchemaTest {
    private val draft = WritingDraftEvidence("Synthetic task", emptyList(), emptyList(), emptyList(), "Synthetic note")

    @Test
    fun `일반 보정 복구 모두 기존 demand 파서 조건을 아홉 닫힌 조합으로 표현한다`() {
        // 준비: 응답 종류에 따라 바뀌는 preservation과 새 demand 제약을 분리한다.
        for (variant in listOf("ordinary", "repaired", "recovered")) {
            val schema = WritingReviewSchema.build(
                draft, "synthetic-candidate", "a".repeat(64),
                revisionHash = if (variant == "repaired") "b".repeat(64) else null,
                recoveryHash = if (variant == "recovered") "c".repeat(64) else null,
            )
            val alternatives = schema.getValue("\$defs").jsonObject.getValue("ProductionDemandCheck")
                .jsonObject.getValue("anyOf").jsonArray

            // 실행 및 검증: code/status마다 하나의 닫힌 object이고 근거·gap이 기존 조건에 맞는다.
            assertEquals(9, alternatives.size)
            for ((code, allowedGap) in WritingReviewDemandCases.gaps) {
                for (status in listOf("PRESENT", "MISSING", "UNSURE")) {
                    val row = alternatives.map { it.jsonObject }.single {
                        val fields = it.getValue("properties").jsonObject
                        fields.getValue("code").jsonObject.getValue("enum") == strings(code) &&
                            fields.getValue("status").jsonObject.getValue("enum") == strings(status)
                    }
                    val properties = row.getValue("properties").jsonObject
                    assertEquals("object", row.getValue("type").jsonPrimitive.content)
                    assertEquals(false, row.getValue("additionalProperties").jsonPrimitive.boolean)
                    assertEquals(setOf("code", "status", "evidenceSegmentIds", "gapCode"),
                        row.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
                    val evidence = properties.getValue("evidenceSegmentIds").jsonObject
                    assertEquals(if (status == "UNSURE") 0 else 1, evidence.getValue("minItems").jsonPrimitive.int)
                    assertEquals(12, evidence.getValue("maxItems").jsonPrimitive.int)
                    assertEquals(strings("O1"), evidence.getValue("items").jsonObject.getValue("enum"))
                    val gap = properties.getValue("gapCode").jsonObject
                    if (status == "PRESENT") {
                        assertEquals(buildJsonArray {
                            add(buildJsonObject { put("type", "string"); put("enum", strings(allowedGap)) })
                            add(buildJsonObject { put("type", "null") })
                        }, gap.getValue("anyOf"))
                    } else assertEquals(buildJsonObject { put("type", "null") }, gap)
                }
            }
        }
    }

    @Test
    fun `code status gap 근거의 전체 조합은 기존 파서의 허용 거부와 일치한다`() {
        // 준비: 3 code × 3 status × null 포함 4 gap × 빈 근거 여부의 72개 합성 조합이다.
        val cases = WritingReviewDemandCases.samples()
        assertEquals(72, cases.size)

        // 실행 및 검증: 파서를 바꾸지 않고 새 Schema에 표현한 조건과 같은 결과를 확인한다.
        for (case in cases) {
            val actual = try {
                WritingReviewParser.parse(case.review)
                null
            } catch (failure: WritingReviewProtocolException) {
                failure.code
            }
            assertEquals(case.expectedFailure, actual, case.name)
        }
    }

    @Test
    fun `실제 TRANSLATION 응답의 잘못된 gap 조합과 빈 확정 근거를 거부한다`() {
        // 준비: 실제 실패 두 응답에 있던 PRECISE_STANCE와 SCOPE_INTERACTION의 잘못된 gap이다.
        val invalid = listOf(
            Triple("PRECISE_STANCE", "RELATIONS_NOT_INTERDEPENDENT", "DEMAND_GAP_MISMATCH"),
            Triple("SCOPE_INTERACTION", "SCOPE_TOO_ROUTINE", "DEMAND_GAP_MISMATCH"),
        )

        // 실행 및 검증: 출력 내용을 고치지 않고 기존 프로토콜 오류를 보존한다.
        for ((code, gap, expected) in invalid) {
            val raw = WritingReviewDemandCases.review(code, "PRESENT", gap, listOf("O1"))
            assertEquals(expected, assertFailsWith<WritingReviewProtocolException> {
                WritingReviewParser.parse(raw)
            }.code)
        }
        assertEquals("DEMAND_EVIDENCE_MISSING", assertFailsWith<WritingReviewProtocolException> {
            WritingReviewParser.parse(WritingReviewDemandCases.review("PRECISE_STANCE", "MISSING", null, emptyList()))
        }.code)
    }

    @Test
    fun `중복 근거 검사와 세 demand 코드의 고유 집합 검사는 파서에 유지한다`() {
        // 준비: uniqueItems를 추가하거나 중복 자료를 제거하지 않는다.
        val duplicate = WritingReviewDemandCases.review("PRECISE_STANCE", "PRESENT", null, listOf("O1", "O1"))
        val schema = WritingReviewSchema.build(draft, "synthetic-candidate", "a".repeat(64))
        val alternatives = schema.getValue("\$defs").jsonObject.getValue("ProductionDemandCheck")
            .jsonObject.getValue("anyOf").jsonArray

        // 실행 및 검증: 지원이 확인되지 않은 Schema 키 대신 기존 검사기가 계속 거부한다.
        alternatives.forEach {
            assertNull(it.jsonObject.getValue("properties").jsonObject.getValue("evidenceSegmentIds").jsonObject["uniqueItems"])
        }
        assertEquals("EVIDENCE_DUPLICATE", assertFailsWith<WritingReviewProtocolException> {
            WritingReviewParser.parse(duplicate)
        }.code)
        val valid = WritingReviewDemandCases.review("PRECISE_STANCE", "PRESENT", null, listOf("O1"))
        val first = valid.getValue("productionDemandChecks").jsonArray.first()
        val repeatedCodes = JsonObject(valid + ("productionDemandChecks" to JsonArray(List(3) { first })))
        assertEquals("DEMAND_SET_INVALID", assertFailsWith<WritingReviewProtocolException> {
            WritingReviewParser.parse(repeatedCodes)
        }.code)
    }

    private fun strings(value: String) = JsonArray(listOf(JsonPrimitive(value)))
}

internal object WritingReviewDemandCases {
    val gaps = linkedMapOf(
        "SCOPE_INTERACTION" to "RELATIONS_NOT_INTERDEPENDENT",
        "PRECISE_STANCE" to "SCOPE_TOO_ROUTINE",
        "COHERENT_REGISTER" to "REGISTER_NOT_INTEGRATED",
    )
    data class Case(val name: String, val review: JsonObject, val expectedFailure: String?)

    fun samples() = buildList {
        for (code in gaps.keys) for (status in listOf("PRESENT", "MISSING", "UNSURE")) {
            for (gap in listOf(null) + gaps.values) for (evidence in listOf(emptyList(), listOf("O1"))) {
                val failure = when {
                    status != "UNSURE" && evidence.isEmpty() -> "DEMAND_EVIDENCE_MISSING"
                    gap != null && (status != "PRESENT" || gap != gaps.getValue(code)) -> "DEMAND_GAP_MISMATCH"
                    else -> null
                }
                add(Case("$code/$status/$gap/evidence=${evidence.size}", review(code, status, gap, evidence), failure))
            }
        }
    }

    fun review(code: String, status: String, gap: String?, evidence: List<String>): JsonObject {
        val source = Json.parseToJsonElement(requireNotNull(javaClass.getResource(
            "/contracts/writing-acceptance-python-golden.json",
        )).readText()).jsonObject.getValue("cases").jsonArray.first().jsonObject.getValue("review").jsonObject
        val demands = gaps.keys.map { current -> buildJsonObject {
            put("code", current)
            put("status", if (current == code) status else "PRESENT")
            put("gapCode", if (current == code) gap?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
            put("evidenceSegmentIds", JsonArray((if (current == code) evidence else listOf("O1")).map(::JsonPrimitive)))
        } }
        return JsonObject(source + ("productionDemandChecks" to JsonArray(demands)))
    }
}
