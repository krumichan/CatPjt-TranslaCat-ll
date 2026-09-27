package jp.co.translacat.languagelearning.shared.diversity

import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import jp.co.translacat.languagelearning.shared.diversity.GenerationFingerprintsTable as Fp

/** 기존 Core의 동일 기능 30일·다른 기능 14일·정확 일치 90일 조회 범위를 보존한다. */
internal class ExposedGenerationFingerprintRepository(
    private val requireTransaction: () -> Unit,
    private val sourceType: String,
) : GenerationFingerprintRepository {
    init {
        require(sourceType in setOf("WRITING", "LISTENING", "SPEAKING", "READING", "VOCABULARY", "LEVEL_TEST"))
    }

    override fun register(
        userId: Long, itemId: Long, learningLanguage: String, content: String,
        metadata: JsonObject, nowUtc: LocalDateTime,
    ): Boolean {
        requireTransaction()
        require(userId > 0 && itemId > 0 && learningLanguage.length in 1..20 && content.isNotBlank())
        val hash = metadata.getValue("contentHash").jsonPrimitive.content
        // 기능별 정규화로 계산한 hash를 그대로 저장하고 공통 기간의 중복만 확인한다.
        require(hash.isNotBlank() && hash.length <= 100)
        val existing = Fp.selectAll().where {
            (Fp.userId eq userId) and (Fp.learningLanguage eq learningLanguage) and
                (Fp.contentHash eq hash) and (Fp.generatedAt greaterEq nowUtc.minusDays(90))
        }.limit(1).any()
        if (existing) return false

        // 새 이력만 추가하며 기존 세션이나 다른 기능의 이력은 수정하지 않는다.
        Fp.insert {
            it[Fp.userId] = userId
            it[Fp.sourceType] = this@ExposedGenerationFingerprintRepository.sourceType
            it[sourceId] = itemId.toString()
            it[Fp.learningLanguage] = learningLanguage
            it[generatedAt] = nowUtc
            it[contentHash] = hash
            it[similarityKey] = metadata.stringOrNull("similarityKey")
            it[contentExcerpt] = content.trim().take(500)
            it[scenarioCategory] = metadata.stringOrNull("scenarioCategory")
            it[communicativeIntent] = metadata.stringOrNull("communicativeIntent")
            it[taskArchetype] = metadata.stringOrNull("taskArchetype")
            it[grammarFocusJson] = (metadata["grammarFocusCodes"] as? JsonArray ?: JsonArray(emptyList())).toString()
            it[semanticSummary] = metadata.stringOrNull("semanticSummary")
            it[policyVersion] = "language-learning-diversity"
        }
        return true
    }

    override fun context(userId: Long, learningLanguage: String, nowUtc: LocalDateTime): JsonObject {
        requireTransaction()
        // 동일 기능과 교차 기능 문맥은 호출 기능을 기준으로 나누고 정확 일치는 함께 조회한다.
        fun rows(days: Long, count: Int, source: String?): List<ResultRow> = Fp.selectAll().where {
            var condition: Op<Boolean> = (Fp.userId eq userId) and (Fp.learningLanguage eq learningLanguage) and
                (Fp.generatedAt greaterEq nowUtc.minusDays(days))
            condition = if (source == "SAME") condition and (Fp.sourceType eq sourceType)
            else if (source == "OTHER") condition and (Fp.sourceType neq sourceType) else condition
            condition
        }.orderBy(Fp.generatedAt, SortOrder.DESC).limit(count).toList()

        val same = rows(30, 80, "SAME")
        val cross = rows(14, 40, "OTHER")
        val exact = rows(90, 200, null).map { it[Fp.contentHash] }.distinct()
        return buildJsonObject {
            put("currentSession", JsonArray(emptyList()))
            put("sameFeatureRecent", JsonArray(same.map { history(it, nowUtc) }))
            put("crossFeatureRecent", JsonArray(cross.map { history(it, nowUtc) }))
            put("exactContentHashes90d", JsonArray(exact.map(::JsonPrimitive)))
        }
    }

    private fun history(row: ResultRow, nowUtc: LocalDateTime): JsonObject = buildJsonObject {
        put("sourceType", row[Fp.sourceType])
        put("content", row[Fp.contentExcerpt] ?: row[Fp.contentHash])
        put("contentHash", row[Fp.contentHash])
        put("scenarioCategory", row[Fp.scenarioCategory]?.let(::JsonPrimitive) ?: JsonNull)
        put("communicativeIntent", row[Fp.communicativeIntent]?.let(::JsonPrimitive) ?: JsonNull)
        put("taskArchetype", row[Fp.taskArchetype]?.let(::JsonPrimitive) ?: JsonNull)
        put("grammarFocusCodes", row[Fp.grammarFocusJson]?.let(Json::parseToJsonElement) ?: JsonArray(emptyList()))
        put("semanticSummary", row[Fp.semanticSummary]?.let(::JsonPrimitive) ?: JsonNull)
        put("ageDays", ChronoUnit.DAYS.between(row[Fp.generatedAt], nowUtc).coerceIn(0, 3650))
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull
}
