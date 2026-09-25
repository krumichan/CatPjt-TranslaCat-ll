package jp.co.translacat.languagelearning.features.growth.api

import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthChange
import jp.co.translacat.languagelearning.support.GrowthFixtures as F
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import kotlin.test.*

class GrowthWireTest {
    private val at=LocalDateTime.parse("2026-09-25T10:00:00")
    private fun operations() = Json.parseToJsonElement(F.envelope().getValue("payloadJson").jsonPrimitive.content).jsonObject.getValue("operations").jsonArray
    private fun operation(index: Int, transform: (JsonObject)->JsonObject): JsonObject {
        val o=operations()[index].jsonObject; return JsonObject(o + ("payload" to transform(o.getValue("payload").jsonObject)))
    }
    @Test fun `BE와 공유하는 여섯 명령의 실제 wire를 해석한다`() {
        val e=GrowthWire.event(F.envelope());assertEquals(6,e.operations.size);assertEquals(123L,e.userId)
        assertIs<GrowthChange.LearningPrepared>(e.operations[0].change)
        assertIs<GrowthChange.WritingScored>(e.operations[1].change)
        assertEquals("EXPRESSIVENESS",(e.operations[5].change as GrowthChange.SpeakingScored).metrics.single().metricType)
    }
    @Test fun `SHA 사용자 source 숫자 문자열 및 알 수 없는 필드는 거부한다`() {
        val e=F.envelope()
        for (bad in listOf(e + ("payloadSha256" to JsonPrimitive("0".repeat(64))), e+("userId" to JsonPrimitive(0)), e+("sequence" to JsonPrimitive("1")),e+("sourceInstanceId" to JsonPrimitive("not-uuid")),e+("unexpected" to JsonPrimitive(true))))
            assertFailsWith<IllegalArgumentException>{GrowthWire.event(JsonObject(bad))}
    }
    @Test fun `semantic hash는 Map key의 순서와 무관하다`() {
        val original=operations()[1].jsonObject
        val reordered=JsonObject(original.entries.reversed().associate { it.toPair() })
        assertEquals(GrowthWire.operation(original,123,at).hash,GrowthWire.operation(reordered,123,at).hash)
    }
    @Test fun `점수의 범위와 개수 및 keyword 문자열 한도를 검사한다`() {
        for (scores in listOf(JsonArray(List(4){JsonPrimitive(80)}),JsonArray(List(5){JsonPrimitive(101)}),JsonArray(List(5){JsonPrimitive("80")}))) {
            assertFailsWith<IllegalArgumentException>{GrowthWire.operation(operation(1){JsonObject(it+("scores" to scores))},123,at)}
        }
        assertFailsWith<IllegalArgumentException>{GrowthWire.operation(operation(3){JsonObject(it+("canonicalKeys" to JsonArray(listOf(JsonPrimitive("x".repeat(201))))))},123,at)}
    }
    @Test fun `SESSION_COACHING은 점수나 metric 쓰기 경로를 사용할 수 없다`() {
        assertFailsWith<IllegalArgumentException>{GrowthWire.operation(operation(5){JsonObject(it+("resultKind" to JsonPrimitive("SESSION_COACHING")))},123,at)}
        assertFailsWith<IllegalArgumentException>{GrowthWire.operation(operation(4){JsonObject(it+("metrics" to JsonArray(listOf(operations()[5].jsonObject.getValue("payload").jsonObject.getValue("metrics").jsonArray[0]))))},123,at)}
    }
    @Test fun `본문에 숨긴 다른 사용자 필드와 불명 metric은 거부한다`() {
        assertFailsWith<IllegalArgumentException>{GrowthWire.operation(operation(4){JsonObject(it+("userId" to JsonPrimitive(456)))},123,at)}
        assertFailsWith<IllegalArgumentException>{GrowthWire.operation(operation(5){p ->
            val m=p.getValue("metrics").jsonArray[0].jsonObject
            JsonObject(p+("metrics" to JsonArray(listOf(JsonObject(m+("metricType" to JsonPrimitive("HIDDEN_SCORE")))))))
        },123,at)}
    }
    @Test fun `동일 operation key의 중복 묶음을 거부한다`() {
        val op=operations()[3];val payload=buildJsonObject{put("operations",JsonArray(listOf(op,op)))}.toString()
        val e=JsonObject(F.envelope()+mapOf("payloadJson" to JsonPrimitive(payload),"payloadSha256" to JsonPrimitive(GrowthWire.hash(payload))))
        assertFailsWith<IllegalArgumentException>{GrowthWire.event(e)}
    }
}
