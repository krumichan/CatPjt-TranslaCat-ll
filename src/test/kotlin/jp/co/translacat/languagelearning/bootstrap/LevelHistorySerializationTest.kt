package jp.co.translacat.languagelearning.bootstrap

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import jp.co.translacat.languagelearning.features.leveltest.api.dto.LevelTestHistoryDetailResponseDto
import jp.co.translacat.languagelearning.features.leveltest.api.dto.LevelTestItemDetailResponseDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class LevelHistorySerializationTest {
    @Test
    fun `통합 이력의 빈 배열과 null 필드는 일반 레벨 이력 응답과 같다`() = testApplication {
        // 준비: 정렬 정답이나 주관식 평가가 없는 문항도 FE가 소비하는 배열 필드를 유지해야 한다.
        val detail = LevelTestHistoryDetailResponseDto(items = listOf(
            LevelTestItemDetailResponseDto(
                questionNumber = 1, complexityBand = 1, audioSubmitted = false,
                answerAudioAvailable = false, referenceAudioAvailable = false,
                modelAnswerAudioAvailable = false, evaluable = true,
            ),
        ))
        application {
            configureSerialization()
            routing {
                get("/level-history") { call.respond(detail) }
                get("/unified-history") { call.respond(encodeOverviewLevelHistory(detail)) }
            }
        }

        // 실행: typed DTO와 미리 만들어진 JsonElement가 실제 Ktor 응답으로 직렬화되는 두 경로를 비교한다.
        val ordinary = Json.parseToJsonElement(client.get("/level-history").bodyAsText())
        val unified = Json.parseToJsonElement(client.get("/unified-history").bodyAsText())

        // 검증: JsonElement에서 사라진 기본 필드는 ContentNegotiation이 복원할 수 없다.
        assertEquals(ordinary, unified)
        val item = unified.jsonObject.getValue("items").jsonArray.single().jsonObject
        listOf("correctOrder", "selectedOptionKeys", "options", "recommendedAnswers", "detailedFeedback",
            "metrics", "strengths", "improvements").forEach { field ->
            assertEquals(JsonArray(emptyList()), item.getValue(field), field)
        }
        assertEquals(JsonNull, unified.jsonObject.getValue("summary"))
        assertEquals(JsonNull, item.getValue("textAnswer"))
        assertEquals(JsonNull, item.getValue("reasonCode"))
    }

    @Test
    fun `빈 통합 이력도 items 배열과 summary null을 유지한다`() = testApplication {
        // 준비
        val detail = LevelTestHistoryDetailResponseDto()
        application {
            configureSerialization()
            routing {
                get("/level-history") { call.respond(detail) }
                get("/unified-history") { call.respond(encodeOverviewLevelHistory(detail)) }
            }
        }

        // 실행
        val ordinary = Json.parseToJsonElement(client.get("/level-history").bodyAsText())
        val unified = Json.parseToJsonElement(client.get("/unified-history").bodyAsText())

        // 검증
        assertEquals(ordinary, unified)
        assertEquals(JsonArray(emptyList()), unified.jsonObject.getValue("items"))
        assertEquals(JsonNull, unified.jsonObject.getValue("summary"))
    }
}
