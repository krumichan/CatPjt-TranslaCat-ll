package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*

/** QA 전용 B2 문단의 의미·참고 답안을 서버 자원에 고정하고 모델에는 변형 선택만 맡긴다. */
internal object CuratedWritingB2Core {
    const val version = "b2-constrained-core-v1"

    private data class Variant(val topicKey: String, val content: JsonObject)

    private val variants: Map<String, Variant> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/writing/b2-constrained-core.json")) {
            "WRITING_B2_CORE_MISSING"
        }
        val root = stream.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
        require(root.keys == setOf("version", "entries") &&
            root.getValue("version").jsonPrimitive.content == version) { "WRITING_B2_CORE_INVALID" }

        // 각 날짜 변형은 같은 의미 골격의 한국어·일본어 표현을 함께 치환한다.
        val entries = root.getValue("entries").jsonArray
        require(entries.isNotEmpty()) { "WRITING_B2_CORE_INVALID" }
        val built = linkedMapOf<String, Variant>()
        val topics = mutableSetOf<String>()
        entries.forEach { raw ->
            val entry = raw.jsonObject
            require(entry.keys == setOf("topicKey", "days", "content")) { "WRITING_B2_CORE_INVALID" }
            val topic = entry.getValue("topicKey").jsonPrimitive.content
            require(topic.matches(Regex("[a-z0-9][a-z0-9-]{2,79}")) && topics.add(topic)) {
                "WRITING_B2_CORE_INVALID"
            }
            val days = entry.getValue("days").jsonArray
            require(days.size == 2) { "WRITING_B2_CORE_INVALID" }
            days.forEach { rawDay ->
                val day = rawDay.jsonObject
                require(day.keys == setOf("key", "ko", "ja")) { "WRITING_B2_CORE_INVALID" }
                val key = day.getValue("key").jsonPrimitive.content
                val id = "$topic-$key"
                require(key.matches(Regex("[a-z0-9-]+")) && id !in built) { "WRITING_B2_CORE_INVALID" }
                val korean = day.getValue("ko").jsonPrimitive.content
                val japanese = day.getValue("ja").jsonPrimitive.content
                require(korean.isNotBlank() && japanese.isNotBlank()) { "WRITING_B2_CORE_INVALID" }
                val content = substitute(entry.getValue("content"), korean, japanese).jsonObject
                built[id] = Variant(topic, content)
            }
        }
        built
    }

    fun supports(topicKey: String): Boolean = variants.values.any { it.topicKey == topicKey }

    fun allowed(topicKey: String, recentPrompts: List<String>, recentCoreIds: Set<String>,
                repairContent: JsonObject?): List<String> {
        val repairing = repairContent?.get("coreId")?.jsonPrimitive?.contentOrNull
        return variants.filter { (id, variant) ->
            variant.topicKey == topicKey && id !in recentCoreIds && id != repairing &&
                variant.content.getValue("prompt").jsonPrimitive.content !in recentPrompts
        }.keys.toList()
    }

    fun content(id: String, topicKey: String): JsonObject {
        val variant = variants[id] ?: error("WRITING_B2_CORE_SELECTION_INVALID")
        require(variant.topicKey == topicKey) { "WRITING_B2_CORE_SELECTION_INVALID" }
        return variant.content
    }

    fun ids(topicKey: String): List<String> = variants.filterValues { it.topicKey == topicKey }.keys.toList()

    fun topic(id: String): String = variants[id]?.topicKey ?: error("WRITING_B2_CORE_SELECTION_INVALID")

    /** 의미 대체는 원본 주제의 날짜 변형 전체와 이미 사용한 코어를 제외한다. */
    fun replacementOptions(originalTopic: String, recentCoreIds: Set<String>): JsonArray = JsonArray(
        variants.filter { (id, variant) -> variant.topicKey != originalTopic && id !in recentCoreIds }
            .map { (id, variant) -> buildJsonObject {
                put("coreId", id)
                put("topicKey", variant.topicKey)
                put("prompt", variant.content.getValue("prompt"))
                put("requirements", variant.content.getValue("requirements"))
            } },
    )

    private fun substitute(value: JsonElement, korean: String, japanese: String): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (_, part) -> substitute(part, korean, japanese) })
        is JsonArray -> JsonArray(value.map { substitute(it, korean, japanese) })
        is JsonPrimitive -> if (value.isString) {
            val rendered = value.content.replace("{{dayKo}}", korean).replace("{{dayJa}}", japanese)
            require("{{" !in rendered && "}}" !in rendered) { "WRITING_B2_CORE_INVALID" }
            JsonPrimitive(rendered)
        } else value
    }
}
