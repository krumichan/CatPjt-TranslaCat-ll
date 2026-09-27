package jp.co.translacat.languagelearning.features.leveltest.infrastructure.persistence

import jp.co.translacat.languagelearning.features.leveltest.application.LevelGenerationHistory
import jp.co.translacat.languagelearning.features.leveltest.application.LevelGenerationHistoryRepository
import jp.co.translacat.languagelearning.features.leveltest.application.LevelHistoryEntry
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelItem
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelSession
import jp.co.translacat.languagelearning.shared.diversity.ExposedGenerationFingerprintRepository
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import jp.co.translacat.languagelearning.shared.diversity.GenerationFingerprintsTable as Fp

internal class ExposedLevelGenerationHistory(private val requireTransaction: () -> Unit) :
    LevelGenerationHistoryRepository {
    private val fingerprints = ExposedGenerationFingerprintRepository(requireTransaction, "LEVEL_TEST")

    override fun context(
        userId: Long, learningLanguage: String, current: List<LevelItem>, recent: List<LevelItem>, now: LocalDateTime,
    ): JsonObject {
        requireTransaction()
        // 정확 일치용 최근200개면 교차 기능의 최근14일40개도 포함한다. Level 문항은 원래 저장소에서 읽는다.
        val other = Fp.selectAll().where {
            (Fp.userId eq userId) and (Fp.learningLanguage eq learningLanguage) and (Fp.sourceType neq "LEVEL_TEST") and
                (Fp.generatedAt greaterEq now.minusDays(90))
        }.orderBy(Fp.generatedAt, SortOrder.DESC).limit(200).map { row ->
            LevelHistoryEntry(
                row[Fp.generatedAt],
                buildJsonObject {
                    put("sourceType", row[Fp.sourceType])
                    put("content", row[Fp.contentExcerpt] ?: row[Fp.contentHash])
                    put("contentHash", row[Fp.contentHash])
                    put("scenarioCategory", row[Fp.scenarioCategory]?.let(::JsonPrimitive) ?: JsonNull)
                    put("communicativeIntent", row[Fp.communicativeIntent]?.let(::JsonPrimitive) ?: JsonNull)
                    put("taskArchetype", row[Fp.taskArchetype]?.let(::JsonPrimitive) ?: JsonNull)
                    put(
                        "grammarFocusCodes",
                        row[Fp.grammarFocusJson]?.let(Json::parseToJsonElement) ?: JsonArray(emptyList()),
                    )
                    put("semanticSummary", row[Fp.semanticSummary]?.let(::JsonPrimitive) ?: JsonNull)
                    put("ageDays", ChronoUnit.DAYS.between(row[Fp.generatedAt], now).coerceAtLeast(0))
                },
            )
        }
        return LevelGenerationHistory.context(current, recent, other, now)
    }

    override fun register(session: LevelSession, item: LevelItem, now: LocalDateTime) {
        // 채택된 문항과 같은 트랜잭션에서 다른 기능이 읽는 공통 이력에 등록한다.
        fingerprints.register(
            session.userId, item.id, session.learningLanguage, LevelGenerationHistory.content(item),
            Json.encodeToJsonElement(item.data.diversityMetadata).jsonObject, now,
        )
    }
}
