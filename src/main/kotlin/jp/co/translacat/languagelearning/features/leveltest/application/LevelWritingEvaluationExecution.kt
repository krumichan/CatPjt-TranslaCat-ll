package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningText
import jp.co.translacat.languagelearning.features.writing.application.WritingEvaluationExecution
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingEvaluationAssets
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingEvaluationProtocolException
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingEvaluationResult
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionFailure
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.text.Normalizer
import java.time.Clock
import java.time.Instant

/** Level Test의 Writing 평가는 기존 공통 rubric으로 실행하되 공식 성장 반영은 세션 완료가 소유한다. */
internal class LevelWritingEvaluationExecution(
    private val writing: WritingEvaluationExecution,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun evaluate(
        session: LevelSession, item: LevelItem, response: LevelSubmission,
        deadline: Instant,
    ): LevelEvaluationData {
        require(item.data.domain == LevelTestDomain.WRITING && response.textAnswer != null)
        val key = "lt:${session.uid}:eval:${item.id}:v${response.revision}:r${response.manualRetryCount}"
        val request = request(key, session, item, response)
        val overallDeadline = minOf(deadline, clock.instant().plusSeconds(60))
        var failure: RuntimeException? = null

        // 기존 Writing 평가의 30초·최대 2회 한도를 전체 Level Test deadline 안에서 사용한다.
        repeat(2) {
            if (clock.instant() >= overallDeadline) return@repeat
            try {
                val result = writing.evaluate(
                    key, "LEVEL_TEST", session.originLanguage, session.learningLanguage,
                    request.toString(), minOf(overallDeadline, clock.instant().plusSeconds(30)),
                )
                return response(key, session, item, result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: WritingEvaluationProtocolException) {
                failure = invalid
            } catch (provider: ModelExecutionFailure) {
                failure = provider
                // 기존 Writing 평가는 Provider 일반 예외를 총 2회 시도했다. 새 실행 protocol 오류는 즉시 구분한다.
                if (!LevelModelFailurePolicy.originalProvider(provider) && provider.code != "MODEL_DEADLINE_EXCEEDED")
                    throw LevelTestException(provider.code, provider.status, "Writing 평가 실행에 실패했습니다.")
            }
        }
        val code = (failure as? ModelExecutionFailure)?.code
            ?: (failure as? WritingEvaluationProtocolException)?.code ?: "AI_EVALUATION_DEADLINE_EXCEEDED"
        val status =
            if (failure == null) 504 else (failure as? ModelExecutionFailure)?.let(LevelModelFailurePolicy::status)
                ?: 502
        throw LevelTestException(
            code, status,
            "Writing 평가를 완료하지 못했습니다.",
        )
    }

    internal fun request(key: String, session: LevelSession, item: LevelItem, response: LevelSubmission): JsonObject =
        buildJsonObject {
            put("requestId", key)
            put("context", "LEVEL_TEST")
            put("writingType", JsonNull)
            put("originLanguage", session.originLanguage)
            put("learningLanguage", session.learningLanguage)
            put("originSentence", item.data.promptText)
            put("userAnswer", checkNotNull(response.textAnswer))
            put("difficulty", "BAND_${item.data.complexityBand}")
            put("keywords", JsonArray(emptyList()))
            put(
                "focusMetrics",
                JsonArray(
                    listOf("MEANING", "GRAMMAR", "VOCABULARY", "NATURALNESS", "EXPRESSION")
                        .map(::JsonPrimitive),
                ),
            )
            put("learningProfileSummary", JsonNull)
            put("taskType", item.data.itemType.name)
            put("translationSourceText", item.data.referencePayload["translationSourceText"] ?: JsonNull)
            listOf("providedFacts", "requiredIntents", "responseConstraints").forEach {
                put(it, item.data.referencePayload[it] as? JsonArray ?: JsonArray(emptyList()))
            }
        }

    internal fun response(
        key: String, session: LevelSession, item: LevelItem,
        result: WritingEvaluationResult,
    ): LevelEvaluationData {
        val payload = result.payload
        fun messages(name: String): List<String> = (payload[name] as? JsonArray).orEmpty()
            .map { it.jsonObject.getValue("originText").jsonPrimitive.content }

        val strengths = feedback(item.data.promptText, messages("strengths"))
        val improvements = feedback(item.data.promptText, messages("weaknesses")).toMutableList()
        val corrections = (payload["corrections"] as? JsonArray).orEmpty().map { it.jsonObject }

        // 원문 반복 피드백은 제외하고 교정 문장과 설명은 기존 순서·중복 규칙으로 이어 붙인다.
        for (correction in corrections) {
            val explanation =
                correction.getValue("explanation").jsonObject.getValue("originText").jsonPrimitive.content.trim()
            val detail = "「${correction.getValue("original").jsonPrimitive.content.trim()}」 → " +
                "「${correction.getValue("corrected").jsonPrimitive.content.trim()}」" +
                if (explanation.isEmpty()) "" else ": $explanation"
            if (detail !in improvements) improvements += detail
        }
        if (result.scores.overall < 90) {
            val explanation =
                payload.getValue("explanation").jsonObject.getValue("originText").jsonPrimitive.content.trim()
            if (explanation.isNotEmpty() && !repetition(
                    item.data.promptText, explanation,
                ) && explanation !in improvements
            )
                improvements += explanation
        }
        val scores = result.scores
        val values = listOf(
            "MEANING" to scores.meaning, "GRAMMAR" to scores.grammar,
            "VOCABULARY" to scores.vocabulary, "NATURALNESS" to scores.naturalness, "EXPRESSION" to scores.expression,
        )
        val details = corrections.map { correction ->
            buildJsonObject {
                put("category", correction.getValue("category"))
                put("severity", "CORRECTION")
                put("original", correction.getValue("original"))
                put("corrected", correction.getValue("corrected"))
                put("explanation", correction.getValue("explanation").jsonObject.getValue("originText"))
            }
        } + messages("weaknesses").map { text ->
            buildJsonObject {
                put("category", "IMPROVEMENT")
                put("severity", "IMPROVEMENT")
                put("original", JsonNull)
                put("corrected", JsonNull)
                put("explanation", text)
            }
        }

        // Level Test 결과만 반환하며 Writing DAILY 성장 이벤트를 발생시키지 않는다.
        return LevelEvaluationData(
            key, session.id, item.id, item.data.domain, item.data.itemType,
            true, scores.overall, 1.0,
            metrics = values.map { (type, score) ->
                buildJsonObject {
                    put("type", type)
                    put("state", "EVALUATED")
                    put("score", score.toDouble())
                    put("confidence", 1.0)
                    put("summary", JsonNull)
                    put("evidence", JsonArray(emptyList()))
                    put("notEvaluableReason", JsonNull)
                }
            },
            strengths = strengths.take(20), improvements = improvements.take(20),
            recommendedAnswers = payload.getValue("recommendedAnswers").jsonArray.map { it.jsonPrimitive.content },
            detailedFeedback = details.take(50),
            assessmentSignals = values.map { (type, score) ->
                buildJsonObject {
                    put("domain", "WRITING")
                    put("metric", type)
                    put("score", score.toDouble())
                    put("confidence", 1.0)
                }
            },
            evaluationVersion = "level-test-evaluation", promptVersion = WritingEvaluationAssets.promptVersion,
            usage = buildJsonObject {
                put("latencyMs", 0); put("inputTokens", 0); put("outputTokens", 0)
                put("provider", JsonNull); put("model", JsonNull); put("promptVersion", JsonNull); put(
                "evaluationVersion", JsonNull,
            )
            },
        )
    }

    private fun feedback(source: String, messages: List<String>): List<String> = messages.map(String::trim)
        .filter { it.isNotEmpty() && !repetition(source, it) }.distinct()

    private fun repetition(source: String, message: String): Boolean {
        val value = compact(message)
        return value.isNotEmpty() && value.codePointCount(0, value.length) >= 8 && compact(source).contains(value)
    }

    private fun compact(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return ListeningText.casefold(normalized).codePoints().toArray().filter {
            Character.isLetterOrDigit(it) ||
                Character.getType(it) in setOf(Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt())
        }.joinToString("") { String(Character.toChars(it)) }
    }
}
