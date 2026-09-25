package jp.co.translacat.languagelearning.features.leveltest.api

import jp.co.translacat.languagelearning.features.leveltest.api.dto.*
import jp.co.translacat.languagelearning.features.leveltest.application.LevelAnswerResult
import jp.co.translacat.languagelearning.features.leveltest.application.LevelHistoryDetail
import jp.co.translacat.languagelearning.features.leveltest.application.LevelReadService
import jp.co.translacat.languagelearning.features.leveltest.application.LevelStatus
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object LevelResponseMapper {
    fun session(value: LevelSession) = LevelSessionResponseDto(
        value.id, value.sessionType, value.status, 20, value.currentQuestionNumber,
        value.currentComplexityBand, value.baseLevelScore?.toDouble(), value.proficiencyBand,
        value.startedAt.toString(), value.completedAt?.toString(),
    )

    fun status(value: LevelStatus) = LevelStatusResponseDto(
        if (value.baseline == null) "LEVEL_TEST_REQUIRED" else "CALIBRATING", value.initialCompleted,
        value.recheckRecommended, value.active?.id, value.active?.currentQuestionNumber,
        value.baseline?.score?.toDouble(), value.baseline?.proficiencyBand,
    )

    fun question(
        session: LevelSession, item: LevelItem, reason: String?, referenceAvailable: Boolean,
    ): LevelQuestionResponseDto {
        val q = item.data
        // 정답 단서가 되는 정렬 원문과 따라 말하기 원문은 활성 문제의 promptText로 노출하지 않는다.
        val prompt = if (q.itemType in setOf(
                LevelTestItemType.GRAMMAR_SENTENCE_ORDER, LevelTestItemType.SPEAKING_REPEAT,
            )
        ) "" else q.promptText
        return LevelQuestionResponseDto(
            session.id, session.sessionType, item.id, item.questionNumber, 20, q.domain, q.itemType, q.complexityBand,
            q.instruction, q.instructionLanguage, q.answerMode, q.answerLanguage, prompt,
            q.options.map { LevelTestOptionResponseDto(it.key, it.text) },
            q.referencePayload.text("emphasisText"), guidance(q.referencePayload), referenceAvailable,
            if (q.itemType == LevelTestItemType.SPEAKING_REPEAT && item.questionNumber == 18) q.referencePayload.text(
                "referenceText",
            ) else null,
            if (q.itemType == LevelTestItemType.SPEAKING_REPEAT) if (item.questionNumber == 18) 2 else 3 else null,
            q.maxAnswerLength, q.maxAudioSeconds, item.status, reason,
        )
    }

    fun answer(value: LevelAnswerResult) = LevelAnswerResultResponseDto(
        value.session.id, value.item.id, value.session.currentQuestionNumber.coerceAtMost(20),
        value.evaluation?.evaluable ?: false, value.evaluation?.score, reason(value),
        value.session.status == LevelTestSessionStatus.COMPLETED, null,
    )

    fun audioAnswer(value: LevelAnswerResult) = LevelAudioAnswerResultResponseDto(
        value.session.id, value.item.id, value.evaluation?.evaluable ?: false,
        value.evaluation?.score, reason(value), value.session.status == LevelTestSessionStatus.COMPLETED, null,
        value.response.audioRetentionUntil?.toString(),
    )

    private fun reason(value: LevelAnswerResult): String? = value.evaluation?.reasonCode
        ?: if (value.item.status == LevelTestItemStatus.EVALUATION_FAILED) "LEVEL_TEST_EVALUATION_FAILED" else null

    fun scores(value: Map<LevelTestDomain, Int>) = LevelTestDomainScoresResponseDto(
        value[LevelTestDomain.VOCABULARY], value[LevelTestDomain.GRAMMAR],
        value[LevelTestDomain.READING], value[LevelTestDomain.LISTENING], value[LevelTestDomain.WRITING],
        value[LevelTestDomain.SPEAKING],
    )

    fun result(value: LevelSession) = LevelTestResultResponseDto(
        value.id, value.sessionType, value.baseLevelScore, value.proficiencyBand, scores(value.domainScores),
        "MY_LEVEL", value.completedAt?.toString(),
    )

    fun summary(value: LevelSession) = LevelTestHistoryItemResponseDto(
        value.id, value.sessionType, value.baseLevelScore, value.proficiencyBand, scores(value.domainScores),
        value.completedAt?.toString(),
    )

    suspend fun detail(value: LevelHistoryDetail, reads: LevelReadService): LevelTestHistoryDetailResponseDto =
        LevelTestHistoryDetailResponseDto(
            summary(value.session),
            value.items.map { history ->
                val item = history.item;
                val q = item.data;
                val r = history.response;
                val e = history.evaluation
                LevelTestItemDetailResponseDto(
                    item.id, item.questionNumber, q.domain, q.itemType, q.complexityBand, q.instruction, q.promptText,
                    q.options.map { LevelTestOptionResponseDto(it.key, it.text) },
                    q.referencePayload.text("emphasisText"), guidance(q.referencePayload),
                    r?.selectedOptionKey, r?.selectedOptionKeys.orEmpty(), r?.textAnswer, r?.audioKey != null,
                    reads.healthy(r?.audioKey), reads.healthy(q.referenceAudio?.objectKey),
                    e?.transcript, e?.recommendedAnswers.orEmpty(), e?.detailedFeedback.orEmpty(),
                    if (q.itemType == LevelTestItemType.SPEAKING_REPEAT) reads.healthy(q.referenceAudio?.objectKey)
                    else q.itemType in setOf(
                        LevelTestItemType.SPEAKING_GUIDED_RESPONSE, LevelTestItemType.SPEAKING_SHORT_RESPONSE,
                    ) && e?.recommendedAnswers?.isNotEmpty() == true,
                    q.internalAnswerKey.correctOptionKey, q.internalAnswerKey.correctOrder, e?.evaluable ?: false,
                    e?.score, e?.confidence, e?.metrics.orEmpty(), e?.strengths.orEmpty(), e?.improvements.orEmpty(),
                    e?.reasonCode,
                )
            },
        )

    fun baseline(value: LevelBaseline) = LevelCompletionDto(
        value.userId, value.sessionId, value.completionId, value.sessionType, value.score,
        value.proficiencyBand, value.completedDate.toString(), value.startedAt.toString(), value.completedAt.toString(),
    )

    fun completion(value: LevelSession) = LevelCompletionDto(
        value.userId, value.id, value.uid, value.sessionType, checkNotNull(value.baseLevelScore),
        checkNotNull(value.proficiencyBand), checkNotNull(value.completedDate).toString(), value.startedAt.toString(),
        checkNotNull(value.completedAt).toString(),
    )

    private fun guidance(value: JsonObject): LevelTestTaskGuidanceResponseDto? {
        val facts = value.strings("providedFacts");
        val intents = value.strings("requiredIntents");
        val constraints = value.strings("responseConstraints")
        return if (facts.isEmpty() && intents.isEmpty() && constraints.isEmpty()) null else LevelTestTaskGuidanceResponseDto(
            facts, intents, constraints,
        )
    }

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.strings(key: String): List<String> = (get(
        key,
    ) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { v -> v.isString }?.contentOrNull?.takeIf { v -> v.isNotBlank() } }
        .orEmpty()
}
