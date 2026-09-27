package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.listening.application.ListeningEvaluationExecution
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningEvaluationContext
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskResult
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningTaskType
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningDictation
import kotlinx.serialization.json.*
import java.time.Instant

/** Listening의 기존 원본 평가를 재사용하되 Level Test 결과 형식과 성장 경계를 유지한다. */
internal class LevelListeningEvaluationExecution(private val execution: ListeningEvaluationExecution) {
    private val json = Json { encodeDefaults = true }
    suspend fun evaluate(
        session: LevelSession, item: LevelItem, response: LevelSubmission,
        deadline: Instant,
    ): LevelEvaluationData {
        val key = "lt:${session.uid}:eval:${item.id}:v${response.revision}:r${response.manualRetryCount}"
        val ref = item.data.referencePayload
        val source = ref.getValue("sourceText").jsonPrimitive.content
        val answer = checkNotNull(response.textAnswer)
        val context = ListeningEvaluationContext(official = false)

        // 받아쓰기는 결정적 정렬로 평가하고 의미 해석만 기존 Mini-model 평가에 전달한다.
        val task = when (item.data.itemType) {
            LevelTestItemType.LISTENING_DICTATION -> ListeningDictation.evaluate(
                source, answer,
                session.learningLanguage, context = context,
            )

            LevelTestItemType.LISTENING_INTERPRETATION -> {
                val request = buildJsonObject {
                    put("requestId", key)
                    put("idempotencyKey", key)
                    put("itemId", item.id)
                    put("attemptId", session.id)
                    put("evaluationPurpose", "PRACTICE")
                    put("answerRevealed", false)
                    put("assistanceUsage", JsonArray(emptyList()))
                    put("policyVersion", "level-test-multiskill")
                    put("modelConfigVersion", "level-test-model-config")
                    put("manualRetryAttempt", response.manualRetryCount)
                    put("sourceText", source)
                    put("referenceMeanings", ref.getValue("referenceMeanings"))
                    put("keyMeaningUnits", ref.getValue("keyMeaningUnits"))
                    put("answer", answer)
                    put("originLanguage", session.originLanguage)
                    put("learningLanguage", session.learningLanguage)
                }
                execution.evaluate(ListeningTaskType.INTERPRETATION, request, context, deadline)
            }

            else -> error("LEVEL_TEST_LISTENING_TEXT_TYPE_INVALID")
        }
        return result(key, session, item, source, task)
    }

    internal fun result(
        key: String, session: LevelSession, item: LevelItem, source: String,
        task: ListeningTaskResult,
    ): LevelEvaluationData {
        val metrics = task.metrics.map { metric ->
            buildJsonObject {
                put("type", metric.type)
                put("state", metric.state)
                put("score", metric.score?.let(::JsonPrimitive) ?: JsonNull)
                put("confidence", metric.confidence)
                put("summary", JsonNull)
                put("evidence", json.encodeToJsonElement(metric.evidence))
                put("notEvaluableReason", metric.notEvaluableReason?.let(::JsonPrimitive) ?: JsonNull)
            }
        }
        val details = task.evidence.map { evidence ->
            detail(
                evidence.metric,
                if (evidence.severity == "INFO") "INFO" else "IMPROVEMENT",
                evidence.recognized, evidence.reference, evidence.feedback,
            )
        } +
            task.omittedMeaningUnits.map {
                detail(
                    "MEANING_OMISSION", "OMISSION", null, it,
                    "답변에서 핵심 의미가 빠졌습니다: $it",
                )
            } +
            task.misunderstoodMeaningUnits.map {
                detail(
                    "MEANING_MISMATCH", "CORRECTION", null, it,
                    "이 의미 단위를 다르게 이해했습니다: $it",
                )
            } +
            task.addedInformation.map {
                detail(
                    "ADDED_INFORMATION", "IMPROVEMENT", it, null,
                    "원문에 없는 정보가 추가되었습니다: $it",
                )
            }

        // PRACTICE 평가는 Listening profile을 갱신하지 않고 세션 완료에 사용할 signal만 반환한다.
        return LevelEvaluationData(
            key, session.id, item.id, LevelTestDomain.LISTENING, item.data.itemType,
            task.evaluable, task.score, task.confidence, metrics = metrics, strengths = task.strengths,
            improvements = task.improvements,
            recommendedAnswers =
                if (item.data.itemType == LevelTestItemType.LISTENING_DICTATION) listOf(source)
                else task.recommendedInterpretations,
            detailedFeedback = details.take(50),
            assessmentSignals = if (!task.evaluable) emptyList() else
                task.metrics.filter { it.state == "EVALUATED" && it.score != null }.map { metric ->
                    buildJsonObject {
                        put("domain", "LISTENING")
                        put("metric", metric.type)
                        put("score", checkNotNull(metric.score))
                        put("confidence", metric.confidence)
                    }
                },
            reasonCode = task.reasonCode,
            evaluationVersion = "listening-evaluation",
            usage = buildJsonObject {
                put("latencyMs", 0); put("inputTokens", 0); put("outputTokens", 0)
                put("provider", JsonNull); put("model", JsonNull); put("promptVersion", JsonNull); put(
                "evaluationVersion", JsonNull,
            )
            },
        )
    }

    private fun detail(
        category: String, severity: String, original: String?, corrected: String?,
        explanation: String,
    ): JsonObject = buildJsonObject {
        put("category", category)
        put("severity", severity)
        put("original", original?.let(::JsonPrimitive) ?: JsonNull)
        put("corrected", corrected?.let(::JsonPrimitive) ?: JsonNull)
        put("explanation", explanation)
    }
}
