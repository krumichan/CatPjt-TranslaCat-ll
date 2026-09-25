package jp.co.translacat.languagelearning.features.leveltest.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 정답·참고 원문을 포함한다. 이 객체를 활성 문항 HTTP 응답에 직접 반환하지 않는다. */
@Serializable
internal data class LevelQuestionData(
    val requestId: String,
    val sessionId: Long,
    val questionNumber: Int,
    val totalQuestions: Int,
    val domain: LevelTestDomain,
    val itemType: LevelTestItemType,
    val complexityBand: Int,
    val instruction: String,
    val instructionLanguage: String,
    val answerMode: LevelTestAnswerMode,
    val answerLanguage: String? = null,
    val promptText: String,
    val options: List<LevelOption> = emptyList(),
    val internalAnswerKey: LevelAnswerKey = LevelAnswerKey(),
    val referencePayload: JsonObject = JsonObject(emptyMap()),
    val diversityMetadata: LevelDiversityMetadata,
    val maxAnswerLength: Int? = null,
    val maxAudioSeconds: Int? = null,
    val generationVersion: String,
    val promptVersion: String? = null,
    val diversitySummary: JsonObject? = null,
    val usage: JsonObject? = null,
    val referenceAudio: LevelReferenceAudio? = null,
)

@Serializable
internal data class LevelOption(val key: String, val text: String)

@Serializable
internal data class LevelAnswerKey(
    val correctOptionKey: String? = null,
    val correctOrder: List<String> = emptyList(),
    val selectionPolicy: String? = null,
    val optionScores: Map<String, Int> = emptyMap(),
)

@Serializable
internal data class LevelReferenceAudio(
    val objectKey: String,
    val contentType: String,
    val durationMs: Int? = null,
    val checksumSha256: String? = null,
)

@Serializable
internal data class LevelDiversityMetadata(
    val scenarioCategory: String,
    val communicativeIntent: String? = null,
    val taskArchetype: String? = null,
    val grammarFocusCodes: List<String> = emptyList(),
    val lexicalFocusCodes: List<String> = emptyList(),
    val semanticSummary: String? = null,
    val requiresBackgroundKnowledge: Boolean? = null,
    val contentHash: String,
    val similarityKey: String,
)
