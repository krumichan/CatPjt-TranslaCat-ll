package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class WritingDiversityValidatorGoldenTest {
    private val golden = Json.parseToJsonElement(
        requireNotNull(javaClass.getResource("/contracts/writing-diversity-python-golden.json")).readText(),
    ).jsonObject

    @Test
    fun `Python 정규화와 3 gram cosine을 재현한다`() {
        for (case in golden.getValue("normalization").jsonArray) {
            val value = case.jsonObject
            assertEquals(
                value.getValue("normalized").jsonPrimitive.content,
                WritingDiversityValidator.normalize(value.getValue("input").jsonPrimitive.content),
            )
        }
        for (case in golden.getValue("cosine").jsonArray) {
            val value = case.jsonObject
            assertEquals(
                value.getValue("value").jsonPrimitive.double,
                WritingDiversityValidator.cosine(
                    value.getValue("left").jsonPrimitive.content,
                    value.getValue("right").jsonPrimitive.content,
                ),
                1e-10,
            )
        }
    }

    @Test
    fun `Python 현재 이력 다양성 판정과 hash 증거를 재현한다`() {
        for (case in golden.getValue("cases").jsonArray) {
            val value = case.jsonObject
            val accepted = value.getValue("accepted").jsonArray.map { entry ->
                entry.jsonObject.let {
                    it.getValue("content").jsonPrimitive.content to it.getValue(
                        "metadata",
                    ).jsonObject
                }
            }
            val decision = WritingDiversityValidator(
                value.getValue("context").jsonObject,
                value.getValue("relaxed").jsonPrimitive.boolean,
            ).validate(
                value.getValue("content").jsonPrimitive.content,
                value.getValue("metadata").jsonObject, accepted,
            )
            val name = value.getValue("name").jsonPrimitive.content
            assertEquals(value.getValue("acceptedDecision").jsonPrimitive.boolean, decision.accepted, name)
            assertEquals(
                value.getValue("reason").let { if (it is JsonNull) null else it.jsonPrimitive.content },
                decision.reason, name,
            )
            assertEquals(value.getValue("finalizedMetadata"), decision.metadata, name)
        }
    }
}
