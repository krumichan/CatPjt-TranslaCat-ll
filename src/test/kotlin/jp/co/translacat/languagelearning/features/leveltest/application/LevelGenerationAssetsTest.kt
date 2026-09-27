package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelGenerationAssets
import kotlinx.serialization.json.*
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LevelGenerationAssetsTest {
    @Test
    fun `Python 생성 특화 Schema와 프롬프트를 모든 문항 유형에서 보존한다`() {
        // 준비: 현재 원본 Python의 문항·band·설계 유무별 60개 합성 계약이다.
        val cases = Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/contracts/leveltest-generation-python-golden.json",
                ),
            ).readText(),
        ).jsonArray
        for (element in cases) {
            val fixture = element.jsonObject
            val request = fixture.getValue("request").jsonObject

            // 실행
            val schema = LevelGenerationAssets.schema(request, fixture.getValue("requirePlan").jsonPrimitive.boolean)
            val prompt = LevelGenerationAssets.prompt(
                request, 1, listOf("합성 이전 거부", "Synthetic rejection"), fixture["designs"] as? JsonArray,
            )

            // 검증: 전체 Schema 내용과 실제 모델 프롬프트가 같고 업로드 capability는 노출하지 않는다.
            val digest =
                MessageDigest.getInstance("SHA-256").digest(sorted(schema).toString().toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            assertEquals(
                fixture.getValue("schemaSha256").jsonPrimitive.content, digest,
                request.getValue("itemType").jsonPrimitive.content + ":" + request.getValue("targetComplexityBand"),
            )
            assertEquals(fixture.getValue("prompt").jsonPrimitive.content, prompt)
            assertFalse(prompt.contains("synthetic-only"))
            assertFalse(prompt.contains("referenceAudioUpload"))
        }
    }

    private fun sorted(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { sorted(it.value) })
        is JsonArray -> JsonArray(value.map(::sorted))
        else -> value
    }
}
