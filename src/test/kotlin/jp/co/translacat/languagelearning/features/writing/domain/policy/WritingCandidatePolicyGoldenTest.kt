package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WritingCandidatePolicyGoldenTest {
    private val cases = Json.parseToJsonElement(
        requireNotNull(
            javaClass.getResource(
                "/contracts/writing-candidate-policy-python-golden.json",
            ),
        ).readText(),
    ).jsonArray

    @Test
    fun `current Python draft checks retain rejection order and note localization`() {
        for (entry in cases) {
            val row = entry.jsonObject
            val request = row.getValue("request").jsonObject
            val draft = WritingCandidatePolicy.parse(row.getValue("draft").jsonObject)
            val type = WritingType.valueOf(request.getValue("writingType").jsonPrimitive.content)
            val name = row.getValue("name").jsonPrimitive.content
            assertEquals(
                row.getValue("reason").takeUnless { it == JsonNull }?.jsonPrimitive?.content,
                WritingCandidatePolicy.reason(request, type, 3, draft), name,
            )
            assertEquals(
                row.getValue("noteNeedsLocalization").jsonPrimitive.boolean,
                WritingCandidatePolicy.noteNeedsLocalization("ko", draft), name,
            )
        }
    }

    @Test
    fun `unknown fields and malformed classification remain candidate schema errors`() {
        val valid = cases.first().jsonObject.getValue("draft").jsonObject
        assertFailsWith<WritingCandidateProtocolException> {
            WritingCandidatePolicy.parse(JsonObject(valid + ("answer" to JsonPrimitive("not allowed"))))
        }
        val metadata = valid.getValue("diversityMetadata").jsonObject
        assertFailsWith<WritingCandidateProtocolException> {
            WritingCandidatePolicy.parse(
                JsonObject(
                    valid + ("diversityMetadata" to
                        JsonObject(metadata + ("requiresBackgroundKnowledge" to JsonPrimitive("false")))),
                ),
            )
        }
    }
}
