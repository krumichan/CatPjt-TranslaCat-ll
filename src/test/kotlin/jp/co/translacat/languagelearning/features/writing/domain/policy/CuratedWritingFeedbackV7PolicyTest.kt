package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.*

class CuratedWritingFeedbackV7PolicyTest {
    private val answer = "毎晩本を読んでいます。"
    private val context = CuratedWritingFeedbackV7Policy.Context(WritingType.FREE, buildJsonObject {}, "ko", "ja")

    @Test
    fun `실제 출장 정상 응답은 원문 경계 공백만 복원한 뒤 전달한다`() {
        replayNormal("reg-f03-actual-business-trip-is-valid-evidence")
    }

    @Test
    fun `실제 정상 활용 응답은 원문 경계 공백만 복원하고 과교정하지 않는다`() {
        replayNormal("reg-f03-normal-fueteimashita-no-overcorrection")
    }

    private fun replayNormal(caseId: String) {
        // 준비: 실제 output_text와 요청의 원문만 담은 고정 fixture이며 API 재호출은 없다.
        val fixture = fixtures().single { it.string("caseId") == caseId }
        val input = fixture.getValue("input").jsonObject
        val task = input.getValue("task").jsonObject
        val requirements = task.getValue("requirements").jsonArray.map { it.jsonObject.string("id") to it.jsonObject.string("text") }
        val ctx = CuratedWritingFeedbackV7Policy.Context(WritingType.valueOf(task.string("writingType")),
            task.getValue("publicPromptAndConditions").jsonObject, task.string("originLanguage"), task.string("learningLanguage"))
        val text = input.getValue("learnerAnswer").jsonObject.string("text")
        val segments = CuratedWritingFeedbackV7Policy.segments(text)
        val raw = fixture.getValue("outputText").jsonObject
        val rawBefore = raw.toString()

        // 실행: 구형 실패를 지우지 않으며 새 버전에서만 source-backed 복원을 적용한다.
        assertFailsWith<IllegalArgumentException> {
            CuratedWritingFeedbackV6Policy.validate(raw, ctx.previous(), requirements, segments)
        }
        val saved = CuratedWritingFeedbackV7Policy.validate(raw, ctx, requirements, segments)

        // 검증: raw의 다른 필드·배열·인용은 그대로이며 바뀐 것은 원문에 실제 있던 경계 공백뿐이다.
        assertEquals(rawBefore, raw.toString())
        assertEquals("curated-writing-feedback-v7", saved.string("policyVersion"))
        assertTrue(saved.getValue("necessaryCorrections").jsonArray.isEmpty())
        val restoredRows = raw.getValue("observations").jsonArray.zip(saved.getValue("observations").jsonArray).map { (before, after) ->
            val source = before.jsonObject
            val storedParts = after.jsonObject.getValue("requirementParts").jsonArray
            val original = requirements.toMap().getValue(source.string("requirementId"))
            assertEquals(original, storedParts.joinToString("") { it.jsonObject.string("requirementQuote") })
            var cursor = 0
            source.getValue("requirementParts").jsonArray.zip(storedParts).forEachIndexed { index, (a, b) ->
                assertEquals(a.jsonObject - "requirementQuote", b.jsonObject - "requirementQuote")
                val quote = a.jsonObject.string("requirementQuote")
                val at = original.indexOf(quote, cursor)
                assertTrue(at >= cursor && original.substring(cursor, at).all(Char::isWhitespace))
                val end = at + quote.length
                val suffix = if (index == storedParts.lastIndex) original.substring(end) else ""
                assertTrue(suffix.all(Char::isWhitespace))
                assertEquals(original.substring(cursor, end) + suffix, b.jsonObject.string("requirementQuote"))
                cursor = end
            }
            JsonObject(source + ("requirementParts" to storedParts))
        }
        val restoredRaw = JsonObject(raw + ("observations" to JsonArray(restoredRows)))
        val expectedV6 = CuratedWritingFeedbackV6Policy.validate(restoredRaw, ctx.previous(), requirements, segments)
        assertEquals(expectedV6 - "policyVersion", saved - "policyVersion")
    }

    @Test
    fun `instructions schema prompt와 이미 정확한 응답은 v6와 동일하다`() {
        // 준비
        val required = listOf("C1" to "상황과 이유")
        val segments = CuratedWritingFeedbackV7Policy.segments(answer)
        val reference = buildJsonObject {}
        val output = result(listOf("상황과 이유"))

        // 실행 / 검증
        assertEquals(CuratedWritingFeedbackV6Policy.instructions, CuratedWritingFeedbackV7Policy.instructions)
        assertEquals(CuratedWritingFeedbackV6Policy.schema(context.previous(), required, segments),
            CuratedWritingFeedbackV7Policy.schema(context, required, segments))
        assertEquals(CuratedWritingFeedbackV6Policy.prompt(context.previous(), reference, answer, required, segments),
            CuratedWritingFeedbackV7Policy.prompt(context, reference, answer, required, segments))
        val old = CuratedWritingFeedbackV6Policy.validate(output, context.previous(), required, segments)
        val current = CuratedWritingFeedbackV7Policy.validate(output, context, required, segments)
        assertEquals(old - "policyVersion", current - "policyVersion")
        assertEquals("curated-writing-feedback-v6", old.string("policyVersion"))
    }

    @Test
    fun `선행 중간 후행 공백은 실제 원문의 문자 그대로 복원한다`() {
        // 준비: 공백 종류를 정규화하지 않는다.
        val original = " \t상황과\n  이유\u3000"

        // 실행
        val saved = save(result(listOf("상황과", "이유")), original)

        // 검증
        val quotes = saved.getValue("observations").jsonArray.single().jsonObject.getValue("requirementParts").jsonArray
            .map { it.jsonObject.string("requirementQuote") }
        assertEquals(listOf(" \t상황과", "\n  이유\u3000"), quotes)
        assertEquals(original, quotes.joinToString(""))
    }

    @Test
    fun `내용 누락 중복 추가 재작성 내부 공백 변화와 빈 part는 계속 거절한다`() {
        // 준비
        val original = "상황과 실제 이유를 전달한다"
        val invalid = listOf(
            listOf("상황과", "이유를 전달한다"), // 실제 내용 누락
            listOf("상황과", "실제", "실제", "이유를 전달한다"), // 중복
            listOf(original, "새 요구"), // 추가
            listOf("상황과 구체적 이유를 전달한다"), // 재작성
            listOf("상황과 실제  이유를 전달한다"), // 내부 공백 추가
            listOf("상황과 실제이유를 전달한다"), // 내부 공백 삭제
            listOf("상황과", "실제 이유를"), // 끝 내용 누락
            listOf(" ", original), emptyList(),
        )

        // 실행 / 검증
        invalid.forEach { quotes -> assertFailsWith<IllegalArgumentException>(quotes.toString()) { save(result(quotes), original) } }
    }

    @Test
    fun `실제 발명 재작성 응답은 공백 복원을 거쳐도 성공으로 변환하지 않는다`() {
        // 준비
        val ids = setOf("reg-f03-reference-only-pair-illness", "reg-gen-g-g1", "reg-gen-g-f2")

        // 실행 / 검증
        fixtures().filter { it.string("caseId") in ids }.forEach { fixture ->
            val input = fixture.getValue("input").jsonObject
            val task = input.getValue("task").jsonObject
            val ctx = CuratedWritingFeedbackV7Policy.Context(WritingType.valueOf(task.string("writingType")),
                task.getValue("publicPromptAndConditions").jsonObject, task.string("originLanguage"), task.string("learningLanguage"))
            val req = task.getValue("requirements").jsonArray.map { it.jsonObject.string("id") to it.jsonObject.string("text") }
            assertFailsWith<IllegalArgumentException> {
                CuratedWritingFeedbackV7Policy.validate(fixture.getValue("outputText"), ctx, req,
                    CuratedWritingFeedbackV7Policy.segments(input.getValue("learnerAnswer").jsonObject.string("text")))
            }
        }
    }

    @Test
    fun `공백 복원은 learner 인용과 관계 판정의 오류를 숨기지 않는다`() {
        // 준비
        val output = result(listOf("상황과", "이유"))
        val row = output.getValue("observations").jsonArray.single().jsonObject
        val parts = row.getValue("requirementParts").jsonArray
        val fake = JsonObject(parts.first().jsonObject + ("evidence" to buildJsonArray {
            add(buildJsonObject { put("segmentId", "S1"); put("quote", "存在しない引用です。") })
        }))
        val fakeQuote = JsonObject(row + ("requirementParts" to JsonArray(listOf(fake, parts.last()))))
        val conflict = JsonObject(row + ("relationChecks" to JsonObject(row.getValue("relationChecks").jsonObject +
            ("stance" to JsonPrimitive("CONFLICT")))))

        // 실행 / 검증
        listOf(fakeQuote, conflict).forEach {
            assertFailsWith<IllegalArgumentException> { save(JsonObject(output + ("observations" to JsonArray(listOf(it)))), "상황과 이유") }
        }
    }

    private fun fixtures() = Json.parseToJsonElement(requireNotNull(javaClass.getResource(
        "/contracts/curated-writing-feedback-v6-boundary-whitespace.json")).readText(Charsets.UTF_8)).jsonArray.map { it.jsonObject }
    private fun result(quotes: List<String>) = buildJsonObject {
        put("observations", buildJsonArray { add(buildJsonObject {
            put("requirementId", "C1")
            put("requirementParts", buildJsonArray { quotes.forEach { quote -> add(buildJsonObject {
                put("requirementQuote", quote); put("status", "PRESENT")
                put("evidence", buildJsonArray { add(buildJsonObject { put("segmentId", "S1"); put("quote", answer) }) })
                put("taskFactIds", buildJsonArray {})
            }) } })
            put("relationChecks", buildJsonObject {
                listOf("causality", "chronology", "specificity", "stance").forEach { put(it, "PRESERVED") }
            })
        }) })
        put("necessaryCorrections", buildJsonArray {}); put("optionalAlternatives", buildJsonArray {}); put("uncertainties", buildJsonArray {})
    }
    private fun save(output: JsonObject, requirement: String) = CuratedWritingFeedbackV7Policy.validate(output, context,
        listOf("C1" to requirement), CuratedWritingFeedbackV7Policy.segments(answer))
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
