package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import java.text.Normalizer
import java.util.*

internal data class WritingDifficultySpec(
    val targetBand: Int,
    val writingType: WritingType,
    val originMaxCharacters: Int,
    val originMaxSurfaceUnits: Int,
    val guidanceMinEntries: Int,
    val guidanceMaxEntries: Int,
    val guidanceMaxCharactersPerEntry: Int,
    val guidanceMaxTotalCharacters: Int,
    val noteMaxCharacters: Int,
    val semanticRecipe: String,
)

/** 기존 Python difficulty_spec.py의 측정 가능한 상한. 의미·난이도 판정은 별도 모델 review다. */
internal object WritingDifficultyPolicy {
    const val version = "writing-difficulty-spec-v2"
    private val cjkOriginMax = listOf(160, 240, 420, 650, 950)
    private val surfaceUnitMax = listOf(2, 3, 4, 5, 6)
    private val guidanceEntryMax = listOf(3, 4, 5, 6, 8)
    val rubric = listOf(
        "FOUNDATION: one direct familiar statement/question; basic words and simple production.",
        "BASIC: familiar communication with one simple reason, sequence or ordinary polite request; limited linking.",
        "INTERMEDIATE: a connected practical reason, condition or time relation with explicit, straightforward scope and suitable everyday/work register. Ordinary politeness alone does not make this band 4; do not require layered qualifications for this band.",
        "UPPER_INTERMEDIATE: a nuanced condition/concession, indirect request or hedge that needs register-sensitive phrasing. A longer practical request or several independent steps do not by themselves reach band 5.",
        "ADVANCED: interacting relations whose scope must be preserved together, a precisely bounded stance/claim/commitment, and coherent controlled register. For example, a commitment depends on a condition but is limited by an exception, or evidence supports only a qualified conclusion that constrains a further action. All needed facts must be supplied or freely chosen by the learner as the mode allows. Removing a qualification changes the claim, not just the sentence length. Familiar vocabulary is sufficient; technical knowledge, verbosity and isolated advanced phrases are not evidence of band 5. A short message can qualify if these demands are actually present.",
    )

    fun targetBand(baseBand: Int?, difficulty: String): Int {
        val base = baseBand ?: 3
        require(base in 1..5)
        val offset = when (difficulty) {
            "REVIEW", "EASY" -> -1; "CHALLENGE" -> 1; else -> 0
        }
        return (base + offset).coerceIn(1, 5)
    }

    fun spec(originLanguage: String, writingType: WritingType, targetBand: Int): WritingDifficultySpec {
        require(targetBand in 1..5)
        val language = originLanguage.replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)
        val factor = if (language in setOf("ko", "ja", "zh")) 1 else 2
        val translation = writingType == WritingType.TRANSLATION
        val guided = writingType == WritingType.GUIDED
        return WritingDifficultySpec(
            targetBand, writingType,
            if (translation) cjkOriginMax[targetBand - 1] * factor else 600 * factor,
            if (translation) surfaceUnitMax[targetBand - 1] else 5,
            if (guided) 1 else 0,
            if (guided) guidanceEntryMax[targetBand - 1] else 0,
            240 * factor, 1800 * factor, 400 * factor, rubric[targetBand - 1],
        )
    }

    fun reason(
        originText: String, focusReason: String,
        providedFacts: List<String>, requiredIntents: List<String>, responseConstraints: List<String>,
        spec: WritingDifficultySpec,
    ): String? {
        val originLength = visibleLength(originText)
        val surfaceUnits = Regex("[。！？!?]+|(?<!\\d)\\.(?=\\s|$)|\\r?\\n+")
            .split(originText.trim()).count { it.isNotBlank() }
        if (originLength < 1 || surfaceUnits < 1) return "SPEC_ORIGIN_EMPTY"
        if (originLength > spec.originMaxCharacters) return "SPEC_ORIGIN_LENGTH"
        if (surfaceUnits > spec.originMaxSurfaceUnits) return "SPEC_ORIGIN_SURFACE_UNITS"
        val groups = listOf(providedFacts, requiredIntents, responseConstraints)
        for (group in groups) {
            if (group.size !in spec.guidanceMinEntries..spec.guidanceMaxEntries) return "SPEC_GUIDANCE_COUNT"
            if (group.any { it.isBlank() || visibleLength(it) > spec.guidanceMaxCharactersPerEntry })
                return "SPEC_GUIDANCE_ENTRY_LENGTH"
        }
        if (groups.flatten()
                .sumOf(::visibleLength) > spec.guidanceMaxTotalCharacters
        ) return "SPEC_GUIDANCE_TOTAL_LENGTH"
        if (visibleLength(focusReason) !in 1..spec.noteMaxCharacters) return "SPEC_NOTE_LENGTH"
        return null
    }

    private fun visibleLength(text: String): Int {
        val value = Normalizer.normalize(text, Normalizer.Form.NFC).trim()
        return value.codePointCount(0, value.length)
    }
}
