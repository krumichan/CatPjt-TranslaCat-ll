package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*

/** 원문의 위치만 나눈다. 문법이나 독립된 의미를 판정하는 분해기가 아니다. */
internal object CuratedWritingRequirementUnits {
    data class Unit(val id: String, val text: String)

    fun compile(requirementId: String, text: String): List<Unit> {
        require(requirementId.matches(Regex("C[1-9][0-9]*")) && text.isNotBlank())
        val units = mutableListOf<Unit>()
        var cursor = 0

        // 선행 공백은 첫 단위에, 단어 뒤 공백은 해당 단위에 붙여 원문 전체를 그대로 보존한다.
        while (cursor < text.length) {
            val start = cursor
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
            while (cursor < text.length && !text[cursor].isWhitespace()) cursor++
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
            units += Unit("$requirementId.U${units.size + 1}", text.substring(start, cursor))
        }
        require(units.joinToString("") { it.text } == text)
        return units
    }

    fun publicUnits(requirementId: String, text: String): JsonArray = JsonArray(compile(requirementId, text).map {
        buildJsonObject { put("id", it.id); put("text", it.text) }
    })

    fun reconstruct(requirementId: String, text: String, parts: JsonArray): JsonArray {
        val units = compile(requirementId, text)
        val indices = units.mapIndexed { index, unit -> unit.id to index }.toMap()
        require(parts.isNotEmpty())
        var cursor = 0

        // 범위는 이 요구의 단위만 순서대로 한 번씩 모두 덮어야 한다. 생성된 요구 문구는 받지 않는다.
        val restored = parts.map { raw ->
            val part = raw.jsonObject
            require(part.keys == setOf("startUnitId", "endUnitId", "status", "evidence", "taskFactIds", "taskConditionIds"))
            val start = indices.getValue(part.getValue("startUnitId").jsonPrimitive.content)
            val end = indices.getValue(part.getValue("endUnitId").jsonPrimitive.content)
            require(start == cursor && end >= start)
            cursor = end + 1
            JsonObject(part - setOf("startUnitId", "endUnitId") + ("requirementQuote" to
                JsonPrimitive(units.subList(start, cursor).joinToString("") { it.text })))
        }
        require(cursor == units.size)
        return JsonArray(restored)
    }
}
