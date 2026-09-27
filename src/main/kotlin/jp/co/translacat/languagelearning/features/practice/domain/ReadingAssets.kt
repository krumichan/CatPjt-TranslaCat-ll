package jp.co.translacat.languagelearning.features.practice.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Python에서 offline으로 내보낸 기존 프롬프트와 schema를 LL 실행 자원으로 소유한다. */
internal object ReadingAssets {
    private val recipes by lazy { Json.parseToJsonElement(text("recipes.json")).jsonObject }
    private val templates by lazy { Json.parseToJsonElement(text("templates.json")).jsonObject }
    private val feedback by lazy { Json.parseToJsonElement(text("feedback.json")).jsonObject }
    fun system(stage: String): String = text("$stage-system.txt")
    fun schema(stage: String): JsonObject = Json.parseToJsonElement(text("$stage-schema.json")).jsonObject
    fun passageRecipe(mode: String, band: Int) = recipes.getValue("passage:$mode:$band").jsonObject
    fun questionRecipe(mode: String, band: Int, skill: String) =
        recipes.getValue("question:$mode:$band:$skill").jsonObject

    fun template(key: String) = templates.getValue(key).jsonObject
    fun retryFeedback(reason: String): String = (feedback[reason] ?: feedback["synthetic-failure-placeholder"])
        ?.jsonPrimitive?.content?.replace("synthetic-failure-placeholder", reason) ?: error("READING_FEEDBACK_MISSING")

    private fun text(name: String): String = checkNotNull(javaClass.getResourceAsStream("/practice/$name")) {
        "Practice 실행 자원을 찾을 수 없습니다."
    }.bufferedReader(Charsets.UTF_8).use { it.readText().replace("\r\n", "\n") }
}
