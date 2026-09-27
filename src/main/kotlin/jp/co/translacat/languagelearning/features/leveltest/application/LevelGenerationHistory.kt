package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelItem
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelSession
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

internal interface LevelGenerationHistoryRepository {
    fun context(
        userId: Long, learningLanguage: String, current: List<LevelItem>, recent: List<LevelItem>, now: LocalDateTime,
    ): JsonObject

    fun register(session: LevelSession, item: LevelItem, now: LocalDateTime)
}

internal data class LevelHistoryEntry(val generatedAt: LocalDateTime, val value: JsonObject)

/** 원본 Core의 기간·상한·최신순 선택을 적용하고 기존 LL 문항 이력도 보존한다. */
internal object LevelGenerationHistory {
    fun context(
        current: List<LevelItem>, recent: List<LevelItem>, other: List<LevelHistoryEntry>, now: LocalDateTime,
    ): JsonObject {
        // 원본 fingerprint에 없던 기존 LL 문항은 저장된 생성 시각과 metadata로 읽는다.
        val own = recent.map { entry(it, now) }
        fun newest(values: List<LevelHistoryEntry>, days: Long, count: Int) = values
            .filter { !it.generatedAt.isBefore(now.minusDays(days)) }.sortedByDescending { it.generatedAt }.take(count)

        val same = newest(own, 30, 80)
        val cross = newest(other, 14, 40)
        val exact = newest(own + other, 90, 200).map { it.value.getValue("contentHash") }.distinct()

        // 개수 제한을 적용한 뒤 Hash를 distinct하는 원본 조회 순서를 유지한다.
        return buildJsonObject {
            put(
                "currentSession",
                JsonArray(current.sortedByDescending { it.createdAt }.take(40).map { entry(it, now).value }),
            )
            put("sameFeatureRecent", JsonArray(same.map { it.value }))
            put("crossFeatureRecent", JsonArray(cross.map { it.value }))
            put("exactContentHashes90d", JsonArray(exact))
        }
    }

    fun content(item: LevelItem): String = (item.data.referencePayload["sourceText"] as? JsonPrimitive)
        ?.contentOrNull?.takeIf { it.isNotBlank() } ?: item.data.promptText

    private fun entry(item: LevelItem, now: LocalDateTime): LevelHistoryEntry {
        val metadata = item.data.diversityMetadata
        return LevelHistoryEntry(
            item.createdAt,
            buildJsonObject {
                put("sourceType", "LEVEL_TEST")
                put("content", content(item).trim().take(500))
                put("contentHash", metadata.contentHash)
                put("scenarioCategory", metadata.scenarioCategory)
                put("communicativeIntent", metadata.communicativeIntent?.let(::JsonPrimitive) ?: JsonNull)
                put("taskArchetype", metadata.taskArchetype?.let(::JsonPrimitive) ?: JsonNull)
                put("grammarFocusCodes", JsonArray(metadata.grammarFocusCodes.map(::JsonPrimitive)))
                put("semanticSummary", metadata.semanticSummary?.let(::JsonPrimitive) ?: JsonNull)
                put("ageDays", ChronoUnit.DAYS.between(item.createdAt, now).coerceAtLeast(0))
            },
        )
    }
}
