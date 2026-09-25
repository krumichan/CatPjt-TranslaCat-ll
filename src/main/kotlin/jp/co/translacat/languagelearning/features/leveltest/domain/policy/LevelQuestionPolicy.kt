package jp.co.translacat.languagelearning.features.leveltest.domain.policy

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import kotlinx.serialization.json.*

internal object LevelQuestionPolicy {
    private val content = LevelContentContract()

    fun validate(question: LevelQuestionData, session: LevelSession, number: Int, band: Int, scenarios: List<String>) {
        val slot = LevelTestRules.slot(number)
        if (question.sessionId != session.id || question.questionNumber != number || question.totalQuestions != 20 ||
            question.domain != slot.domain || question.itemType != slot.itemType || question.complexityBand != band ||
            question.instructionLanguage != session.learningLanguage || question.generationVersion.isBlank() ||
            question.diversityMetadata.requiresBackgroundKnowledge == true || question.diversityMetadata.contentHash.isBlank() ||
            question.diversityMetadata.contentHash.length > 64 || question.diversityMetadata.similarityKey.isBlank() ||
            question.diversityMetadata.similarityKey.length > 255 || question.instruction.length > 10000 || question.promptText.length > 20000
        ) {
            invalid("QUESTION_CONTRACT_MISMATCH")
        }
        if (scenarios.isNotEmpty() && question.diversityMetadata.scenarioCategory.trim().uppercase() !in scenarios) {
            invalid("SCENARIO_BALANCE_MISMATCH")
        }
        val expectedLanguage = when (question.answerMode) {
            LevelTestAnswerMode.CHOICE -> null
            else -> if (question.itemType == LevelTestItemType.LISTENING_INTERPRETATION) session.originLanguage else session.learningLanguage
        }
        if (expectedLanguage != question.answerLanguage) invalid("ANSWER_LANGUAGE_MISMATCH")
        val key = question.internalAnswerKey
        val health = content.inspectValues(
            LevelContentContract.LevelTestDomain.valueOf(question.domain.name),
            LevelContentContract.LevelTestItemType.valueOf(question.itemType.name),
            question.instruction, question.instructionLanguage, session.learningLanguage,
            LevelContentContract.LevelTestAnswerMode.valueOf(question.answerMode.name),
            question.answerLanguage, question.promptText,
            question.options.map { LevelContentContract.Option(it.key, it.text) },
            LevelContentContract.AnswerKey(
                key.correctOptionKey, key.correctOrder, key.selectionPolicy, key.optionScores,
            ),
            question.referencePayload.mapValues { (_, value) -> value.toPlainValue() },
            question.maxAnswerLength, question.maxAudioSeconds,
        )
        if (!health.valid) invalid(health.reason)
        if (LevelTestRules.requiresAudio(
                question.itemType,
            ) && (question.referenceAudio == null || question.referenceAudio.objectKey.isBlank())
        ) {
            invalid("REFERENCE_AUDIO_MISSING")
        }
    }

    fun validateEvaluation(value: LevelEvaluationData, session: LevelSession, item: LevelItem) {
        if (value.sessionId != session.id || value.itemId != item.id || value.domain != item.data.domain ||
            value.itemType != item.data.itemType || (value.score != null && value.score !in 0..100) ||
            (value.confidence != null && (!value.confidence.isFinite() || value.confidence !in 0.0..1.0)) ||
            (value.evaluable && value.score == null) || value.evaluationVersion.isBlank()
        ) invalid("EVALUATION_CONTRACT_MISMATCH")
    }

    private fun JsonElement.toPlainValue(): Any? = when (this) {
        JsonNull -> null
        is JsonObject -> mapValues { (_, value) -> value.toPlainValue() }
        is JsonArray -> map { it.toPlainValue() }
        is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    }

    private fun invalid(reason: String?): Nothing = throw LevelTestException(
        "AI_SCHEMA_INVALID", 502, "레벨 테스트 AI 계약 검증에 실패했습니다: ${reason ?: "UNKNOWN"}",
    )
}
