package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.CuratedWritingFeedbackTestPayload
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingLanguageFormEvidenceTest {
    private val answer = "可能であれば、返却日を6月22日に変更していただけるますか。"
    private val segments = CuratedWritingFeedbackPolicy.segments(answer)

    @Test
    fun `실제 활용 오류의 작은 삭제와 문맥 포함 교정은 같은 독립 근거로 지지한다`() {
        // 준비: FG04와 같은 잘못된 활용에서 실제 바뀌는 원문 위치는 오류 core 안에 있다.
        val evidence = validate(findings(finding("変更していただけるますか", "いただけるます")))
        val patches = listOf(
            correction("る", ""),
            correction("るます", "ます"),
            correction("変更していただけるますか", "変更していただけますか"),
        )

        // 실행·검증: patch에 포함한 문맥 길이와 무관하게 실제 오류 위치를 고친 경우만 지지한다.
        patches.forEach { patch ->
            val corrections = JsonArray(listOf(patch))
            assertTrue(CuratedWritingLanguageFormEvidence.issues(corrections, evidence, segments).isEmpty())
            CuratedWritingLanguageFormEvidence.requireCoverage(corrections, evidence, segments)
        }
    }

    @Test
    fun `정상 현재 잔존 상태를 문체 선호로 바꾸는 필수 교정은 독립 오류 근거 없으면 거절한다`() {
        // 준비: 과거 사건 뒤 현재 잔존 상태는 그 자체로 잘못된 활용이 아니다.
        val stateSegments = CuratedWritingFeedbackPolicy.segments("完成した植木鉢は図書館に置いたままです。")
        val corrections = findings(correction("置いたままです", "置いてきました"))

        // 실행·검증: 상태를 다른 사건으로 바꾸는 제안을 근거 없는 LANGUAGE_FORM으로 수용하지 않는다.
        assertTrue(CuratedWritingLanguageFormEvidence.issues(corrections, findings(), stateSegments).isNotEmpty())
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingLanguageFormEvidence.requireCoverage(corrections, findings(), stateSegments)
        }
    }

    @Test
    fun `독립 검사에서 발견한 실제 오류의 필수 교정을 통째로 삭제해도 통과하지 않는다`() {
        // 준비: 오류 발견은 보존하지만 전체 교정 배열을 비운 복구 응답이다.
        val evidence = validate(findings(finding("変更していただけるますか", "いただけるます")))

        // 실행·검증: 출력 교정의 근거 검사뿐 아니라 독립 오류의 교정 coverage도 요구한다.
        assertTrue(CuratedWritingLanguageFormEvidence.issues(findings(), evidence, segments).isNotEmpty())
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingLanguageFormEvidence.requireCoverage(findings(), evidence, segments)
        }
    }

    @Test
    fun `긴 인용과 patch가 오류를 포함해도 실제 변경 위치가 무관하면 지지하지 않는다`() {
        // 준비: 인용과 patch 전체에는 오류 core가 있지만 실제 변경은 앞의 장소에만 있다.
        val original = "昨日は図書館へ行き、返却日を6月22日に変更していただけるますかと聞きました。"
        val known = CuratedWritingFeedbackPolicy.segments(original)
        val evidence = CuratedWritingLanguageFormEvidence.validate(
            findings(finding(original, "いただけるます")), known,
        )
        val unrelated = findings(correction(original, original.replace("図書館", "駅")))

        // 실행·검증: 공통 prefix/suffix를 제외한 실제 변경 범위로 비교해 넓은 문맥 인용의 오인증을 막는다.
        assertTrue(CuratedWritingLanguageFormEvidence.issues(unrelated, evidence, known).isNotEmpty())
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingLanguageFormEvidence.requireCoverage(unrelated, evidence, known)
        }
    }

    @Test
    fun `동일 문구여도 다른 문장이나 다른 오류 종류의 finding은 교정 근거가 되지 않는다`() {
        // 준비: 두 문장에 같은 잘못된 활용이 있지만 독립 finding은 첫 문장의 INFLECTION에만 있다.
        val known = CuratedWritingFeedbackPolicy.segments(
            "変更していただけるますか。別の日も変更していただけるますか。",
        )
        val evidence = CuratedWritingLanguageFormEvidence.validate(
            findings(finding("変更していただけるますか", "いただけるます")), known,
        )
        val otherSegment = findings(correction("るます", "ます", segmentId = "S2"))
        val otherReason = findings(correction("るます", "ます", reason = "SPELLING"))

        // 실행·검증: 문자열의 유사성만으로 다른 원문 위치나 다른 분류의 권한을 공유하지 않는다.
        for (invalid in listOf(otherSegment, otherReason)) {
            assertTrue(CuratedWritingLanguageFormEvidence.issues(invalid, evidence, known).isNotEmpty())
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingLanguageFormEvidence.requireCoverage(invalid, evidence, known)
            }
        }
    }

    @Test
    fun `삽입만 하는 교정은 오류 core와 접한 원문 경계에서 지지한다`() {
        // 준비: 누락된 조사의 삽입점은 원문 문자의 넓이가 없어 core 경계에 닿을 수 있다.
        val known = CuratedWritingFeedbackPolicy.segments("昨日、図書館本を返しました。")
        val patch = findings(correction("図書館本", "図書館に本", reason = "SYNTAX"))
        val anchors = listOf("図書館", "本")

        // 실행·검증: 삽입점을 왼쪽 또는 오른쪽의 실제 원문 core로 고정한 경우 둘 다 허용한다.
        anchors.forEach { anchor ->
            val evidence = CuratedWritingLanguageFormEvidence.validate(
                findings(finding("図書館本を返しました", anchor, reason = "SYNTAX")), known,
            )
            assertTrue(CuratedWritingLanguageFormEvidence.issues(patch, evidence, known).isEmpty())
            CuratedWritingLanguageFormEvidence.requireCoverage(patch, evidence, known)
        }
    }

    @Test
    fun `위조 원문 외부 문장 중복 및 불완전 finding은 수용하지 않는다`() {
        // 준비: 형식이 유사해도 원문 고유 위치와 실제 core를 확인할 수 없는 근거를 만든다.
        val valid = finding("変更していただけるますか", "いただけるます")
        val invalid = listOf(
            findings(finding("変更していただきますか", "いただきます")),
            findings(finding("変更していただけるますか", "読んだます")),
            findings(finding("変更していただけるますか", "いただけるます", segmentId = "S9")),
            findings(valid, valid),
            findings(finding("変更していただけるますか", "いただけるます", reason = "NONE")),
            findings(JsonObject(valid - "errorCore")),
            findings(JsonObject(valid + ("status" to JsonPrimitive("MET")))),
        )

        // 실행·검증: 유효한 finding은 그대로 보존하며 잘못된 항목을 조용히 삭제하지 않는다.
        assertEquals(findings(valid), validate(findings(valid)))
        invalid.forEachIndexed { index, value ->
            assertFailsWith<IllegalArgumentException>("finding case $index") { validate(value) }
        }
    }

    @Test
    fun `인용과 core는 해당 문맥에서 각각 고유하며 허용 길이 안에 있어야 한다`() {
        // 준비: 반복 인용, 반복 core, 긴 인용, 긴 core를 각각 독립적으로 만든다.
        val cases = listOf(
            "同じと言って、同じと答えました。" to finding("同じ", "同じ"),
            "誤りと誤りを説明しました。" to finding("誤りと誤りを説明しました", "誤り"),
            ("あ".repeat(160) + "誤。") to finding("あ".repeat(160) + "誤", "誤"),
            ("あ".repeat(33) + "。") to finding("あ".repeat(33), "あ".repeat(33)),
        )

        // 실행·검증: 긴 근거나 중복 문자열로 오류의 실제 위치를 모호하게 만들 수 없다.
        cases.forEachIndexed { index, (text, error) ->
            assertFailsWith<IllegalArgumentException>("unique/length case $index") {
                CuratedWritingLanguageFormEvidence.validate(findings(error), CuratedWritingFeedbackPolicy.segments(text))
            }
        }
    }

    @Test
    fun `과제 T와 P 충돌 교정은 문법 오류 finding을 요구하거나 대신 소비하지 않는다`() {
        // 준비: 과제 권한 교정은 언어형태 오류와 별도 경로다.
        val taskCorrections = findings(
            JsonObject(correction("午後6時", "午後5時") + mapOf(
                "issueType" to JsonPrimitive("TASK_FACT_CONFLICT"),
                "sourceFactId" to JsonPrimitive("T1"), "formReason" to JsonPrimitive("NONE"),
            )),
            JsonObject(correction("6月23日", "6月22日") + mapOf(
                "issueType" to JsonPrimitive("TASK_CONDITION_CONFLICT"),
                "sourceAuthorityId" to JsonPrimitive("P1"), "formReason" to JsonPrimitive("NONE"),
            )),
        )
        val evidence = validate(findings(finding("変更していただけるますか", "いただけるます")))

        // 실행·검증: T/P의 실제 권한 검사는 원검증기에 맡기되 독립 문법 오류 교정을 대신 충족하지 않는다.
        assertTrue(CuratedWritingLanguageFormEvidence.issues(taskCorrections, findings(), segments).isEmpty())
        CuratedWritingLanguageFormEvidence.requireCoverage(taskCorrections, findings(), segments)
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingLanguageFormEvidence.requireCoverage(taskCorrections, evidence, segments)
        }
    }

    @Test
    fun `독립 오류 schema는 실제 문장 ID와 세 오류 종류 및 네 필드를 고정한다`() {
        // 준비: 응답 schema는 별도 상태나 교정안을 받지 않는 좁은 오류 관찰 계약이다.
        val schema = CuratedWritingLanguageFormEvidence.schema(segments)
        val item = schema.getValue("items").jsonObject
        val properties = item.getValue("properties").jsonObject

        // 실행·검증: 모델이 임의 문장 ID나 NONE 또는 별도 판정 필드를 만들지 못하게 한다.
        assertEquals("array", schema.getValue("type").jsonPrimitive.content)
        assertEquals(setOf("segmentId", "quote", "errorCore", "formReason"), properties.keys)
        assertEquals(properties.keys, item.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(false, item.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals(listOf("S1"), properties.getValue("segmentId").jsonObject.getValue("enum").jsonArray
            .map { it.jsonPrimitive.content })
        assertEquals(setOf("SPELLING", "INFLECTION", "SYNTAX"), properties.getValue("formReason").jsonObject
            .getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun `기존 전체 검증을 통과한 부당한 언어형태 교정도 현재 독립 근거 게이트는 차단한다`() {
        // 준비: 실제 과제 충돌은 있으나 학습자의 현재 잔존 상태 문장 자체는 문법적으로 정상이다.
        val text = "完成した植木鉢は図書館に置いたままです。"
        val known = CuratedWritingFeedbackPolicy.segments(text)
        val requirements = listOf("C1" to "완성한 화분을 집에 가져갔다는 내용을 포함할 것")
        val context = CuratedWritingFeedbackPolicy.Context(WritingType.GUIDED, buildJsonObject {
            put("originText", "완성한 화분을 집에 가져간 경험을 쓰세요.")
            put("providedFacts", buildJsonArray { add("완성한 화분을 집에 가져갔다.") })
            put("requiredIntents", buildJsonArray { add("화분을 집에 가져갔다는 사실을 포함하기") })
        }, "ko", "ja")
        val reference = buildJsonObject { put("referenceAnswers", buildJsonArray {}); put("alternativeNote", "") }
        val input = Json.parseToJsonElement(CuratedWritingFeedbackV11Policy.prompt(
            context, reference, text, requirements, known,
        )).jsonObject
        val base = CuratedWritingFeedbackTestPayload.v11Response(input)
        val row = base.getValue("observations").jsonObject.getValue("C1").jsonObject
        val part = row.getValue("parts").jsonArray.single().jsonObject
        val conflictingPart = JsonObject(part + ("status" to JsonPrimitive("CONFLICT")))
        val relations = row.getValue("relationChecks").jsonObject
        val raw = JsonObject(base + mapOf(
            "observations" to buildJsonObject {
                put("C1", JsonObject(row + mapOf(
                    "parts" to findings(conflictingPart),
                    "relationChecks" to JsonObject(relations + ("stance" to JsonPrimitive("CONFLICT"))),
                )))
            },
            "necessaryCorrections" to findings(JsonObject(correction("置いたままです", "置いてきました") +
                ("learningText" to JsonPrimitive("過去の話に時制をそろえるため、過去形に変えます。")))),
        ))
        val independent = buildJsonObject {
            put("statuses", buildJsonObject { put("C1", "UNMET") })
            put("evidence", buildJsonObject { put("C1", findings()) })
            put(CuratedWritingLanguageFormEvidence.key, findings())
        }

        // 실행: 같은 raw 출력은 기존 V11 및 과거 V12 계약을 통과한다.
        val legacy = CuratedWritingFeedbackV12Policy.validateFinal(
            raw, JsonObject(independent - CuratedWritingLanguageFormEvidence.key), context, requirements, known,
        )
        val current = CuratedWritingFeedbackV12Policy.validateCurrentIndependent(
            independent, text, requirements, known,
        )

        // 검증: 현재 경로는 점수나 기대값을 바꾸지 않고 독립 오류 지지가 없는 필수 교정을 거절한다.
        assertEquals("curated-writing-feedback-v12", legacy.getValue("policyVersion").jsonPrimitive.content)
        assertEquals("UNMET", legacy.getValue("observations").jsonArray.single().jsonObject
            .getValue("status").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV12Policy.validateCurrentFinal(raw, current, context, requirements, known)
        }
    }

    private fun finding(
        quote: String,
        core: String,
        segmentId: String = "S1",
        reason: String = "INFLECTION",
    ) = buildJsonObject {
        put("segmentId", segmentId); put("quote", quote); put("errorCore", core); put("formReason", reason)
    }

    private fun correction(
        original: String,
        replacement: String,
        segmentId: String = "S1",
        reason: String = "INFLECTION",
    ) = buildJsonObject {
        put("requirementId", "C1"); put("segmentId", segmentId)
        put("patch", buildJsonObject { put("original", original); put("replacement", replacement) })
        put("issueType", "LANGUAGE_FORM"); put("sourceFactId", ""); put("sourceAuthorityId", "")
        put("formReason", reason); put("learningText", "実際の活用の誤りを直して、自然な依頼の形にします。")
    }

    private fun findings(vararg values: JsonElement) = JsonArray(values.toList())

    private fun validate(value: JsonElement) = CuratedWritingLanguageFormEvidence.validate(value, segments)
}
