package jp.co.translacat.languagelearning.features.writing.domain.model

internal enum class WritingDifficulty { REVIEW, NORMAL, CHALLENGE }

internal data class WritingItem(
    val id: Long,
    val setId: Long,
    val userId: Long,
    val order: Int,
    val difficulty: WritingDifficulty,
    val originText: String,
    val keywordsJson: String,
    val focusMetricsJson: String,
    val focusReason: String,
    val providedFactsJson: String?,
    val requiredIntentsJson: String?,
    val responseConstraintsJson: String?,
    val languageComplexityBand: Int? = null,
    val diversityMetadataJson: String? = null,
)

internal data class NewWritingItem(
    val order: Int,
    val difficulty: WritingDifficulty,
    val originText: String,
    val keywords: List<String>,
    val focusMetrics: List<String>,
    val focusReason: String,
    val providedFacts: List<String> = emptyList(),
    val requiredIntents: List<String> = emptyList(),
    val responseConstraints: List<String> = emptyList(),
    val languageComplexityBand: Int? = null,
    val diversityMetadataJson: String? = null,
) {
    fun validateFor(type: WritingType) {
        require(
            order > 0 && originText.isNotBlank() && originText.length <= 2000 &&
                focusReason.isNotBlank() && focusReason.length <= 1000 && keywords.size <= 20 && focusMetrics.size <= 5 &&
                focusMetrics.all { it in setOf("MEANING", "GRAMMAR", "VOCABULARY", "NATURALNESS", "EXPRESSION") },
        ) {
            "WRITING_ITEM_INVALID"
        }
        val guides = listOf(providedFacts, requiredIntents, responseConstraints)
        require(guides.all { it.size <= 12 }) { "WRITING_GUIDE_INVALID" }
        require(guides.flatten().none(String::isBlank)) { "WRITING_GUIDE_INVALID" }
        if (type == WritingType.GUIDED) require(guides.all(List<String>::isNotEmpty)) { "WRITING_GUIDE_MISSING" }
        else require(guides.all(List<String>::isEmpty)) { "WRITING_GUIDE_UNEXPECTED" }
        require(languageComplexityBand == null || languageComplexityBand in 1..5) { "WRITING_BAND_INVALID" }
        if (diversityMetadataJson != null) {
            require(
                kotlinx.serialization.json.Json.parseToJsonElement(diversityMetadataJson) is
                    kotlinx.serialization.json.JsonObject,
            ) { "WRITING_DIVERSITY_METADATA_INVALID" }
        }
    }
}
