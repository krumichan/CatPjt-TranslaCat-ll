package jp.co.translacat.languagelearning.features.overview.application

import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.LocalDateTime

internal fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.count(key: String) = number(key)?.toInt() ?: 0
internal fun JsonObject.rows(key: String) = (get(key) as? JsonArray)?.map { it.jsonObject }.orEmpty()
internal fun JsonObject.objects(key: String) = get(key) as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.date(key: String) = LocalDate.parse(checkNotNull(text(key)))
internal fun JsonObject.time(key: String) = LocalDateTime.parse(checkNotNull(text(key)))
internal fun round2(value: Double) = Math.round(value * 100.0) / 100.0
internal fun round3(value: Double) = Math.round(value * 1000.0) / 1000.0
internal fun recency(index: Int, count: Int) = if (count <= 1) 1.0 else round3(1.0 - .5 * index / (count - 1))
internal fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())

/** 각 기능이 확정한 공식 평가 사실만 받으며 집계 중 모델이나 외부 HTTP를 호출하지 않는다. */
internal data class OverviewFacts(
    val writing: JsonObject,
    val speaking: JsonObject,
    val speakingGrowth: List<JsonObject>,
    val listening: JsonObject,
    val practice: JsonObject,
)
