package jp.co.translacat.languagelearning.features.leveltest.infrastructure.ai

import jp.co.translacat.languagelearning.features.leveltest.application.*
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import jp.co.translacat.languagelearning.features.listening.application.ListeningEvaluationExecution
import jp.co.translacat.languagelearning.features.writing.application.WritingEvaluationExecution
import jp.co.translacat.languagelearning.shared.ai.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.time.Instant

/** 업무 생성·평가는 LL에서 실행하고 Python에는 범용 모델·음성 실행만 요청한다. */
internal class HttpLevelTestAi(
    baseUrl: String, apiKey: String, private val timeoutSeconds: Long, concurrency: Int = 2,
    publishAudio: suspend (LevelAudioUpload, ByteArray, String) -> Unit = { _, _, _ ->
        throw LevelTestException("LEVEL_TEST_AUDIO_STORAGE_UNAVAILABLE", 503, "Reference Audio 저장소가 준비되지 않았습니다.")
    },
) : LevelTestAi, AutoCloseable {
    private val permits = Semaphore(concurrency)
    private val model = HttpModelExecution(baseUrl, apiKey)
    private val speech = HttpSpeechExecution(baseUrl, apiKey)
    private val transcription = HttpSpeechTranscription(baseUrl, apiKey)
    private val speakingEvaluation = LevelSpeakingEvaluationExecution(transcription, model)
    private val writingEvaluation = LevelWritingEvaluationExecution(WritingEvaluationExecution(model))
    private val listeningEvaluation = LevelListeningEvaluationExecution(ListeningEvaluationExecution(model))
    private val generation = LevelGenerationExecution(model, speech, publishAudio)

    init {
        require(apiKey.isNotBlank() && '\r' !in apiKey && '\n' !in apiKey && concurrency in 1..8)
    }

    override suspend fun generate(context: LevelGenerationContext, upload: LevelAudioUpload?): LevelQuestionData {
        val ctx = context;
        val session = ctx.session;
        val slot = LevelTestRules.slot(ctx.number)
        val payload = buildJsonObject {
            put("requestId", ctx.requestKey); put("idempotencyKey", ctx.requestKey); put("sessionId", session.id)
            put("questionNumber", ctx.number); put("totalQuestions", 20); put("domain", slot.domain.name); put(
            "itemType", slot.itemType.name,
        )
            put("originLanguage", session.originLanguage); put("learningLanguage", session.learningLanguage); put(
            "targetComplexityBand", ctx.band,
        )
            put(
                "previousResults",
                JsonArray(
                    ctx.previous.map { (item, evaluation) ->
                        buildJsonObject {
                            put("questionNumber", item.questionNumber); put("domain", item.data.domain.name); put(
                            "itemType", item.data.itemType.name,
                        )
                            put("score", evaluation.score?.let(::JsonPrimitive) ?: JsonNull); put(
                            "complexityBand", item.data.complexityBand,
                        ); put("evaluable", evaluation.evaluable)
                        }
                    },
                ),
            )
            put(
                "diversityContext",
                ctx.diversityContext ?: LevelGenerationHistory.context(
                    ctx.currentItems, ctx.recentItems, emptyList(), ctx.nowUtc,
                ),
            )
            put("preferredScenarioCategories", strings(ctx.scenarios)); put(
            "policyVersion", LevelTestRules.GENERATION_POLICY,
        ); put("modelConfigVersion", LevelTestRules.MODEL_CONFIG)
            put(
                "referenceAudioUpload",
                upload?.let {
                    buildJsonObject {
                        put("uploadUrl", it.uploadUrl); put("objectKey", it.objectKey); put(
                        "contentType", it.contentType,
                    ); put("voice", "marin"); put("playbackSpeed", "NORMAL")
                    }
                } ?: JsonNull,
            )
        }
        return generic { generation.generate(payload, upload, Instant.now().plusSeconds(timeoutSeconds)) }
    }

    override suspend fun evaluate(
        session: LevelSession, item: LevelItem, response: LevelSubmission, audio: ByteArray?,
    ): LevelEvaluationData {
        // 텍스트 업무 평가와 판정은 LL이 소유하며 Python에는 모델 실행만 전달한다.
        val deadline = Instant.now().plusSeconds(timeoutSeconds)
        if (item.data.domain == LevelTestDomain.WRITING) return generic {
            writingEvaluation.evaluate(session, item, response, deadline)
        }
        if (item.data.domain == LevelTestDomain.LISTENING) return generic {
            listeningEvaluation.evaluate(session, item, response, deadline)
        }
        return generic {
            speakingEvaluation.evaluate(session, item, response, checkNotNull(audio), deadline)
        }
    }

    override suspend fun synthesize(
        session: LevelSession, item: LevelItem, text: String, policy: LevelTestContext,
    ): LevelAudioBytes {
        val key = "lt:${session.uid}:model:${item.id}"
        val deadline = Instant.now().plusSeconds(timeoutSeconds)
        return generic {
            // 같은 전체 deadline 안에서 기존 기술 재시도 한도를 적용하고 음성은 LL이 보관한다.
            var attempt = 0
            while (true) {
                try {
                    val result = speech.synthesize(
                        SpeechSynthesisCommand(
                            text, "marin", session.learningLanguage,
                            "NORMAL", deadline, key,
                        ),
                    )
                    return@generic LevelAudioBytes(result.audioBytes, result.contentType)
                } catch (failure: ModelExecutionFailure) {
                    if (!failure.retryable || attempt >= policy.automaticRetryLimit || Instant.now() >= deadline) throw failure
                    attempt++
                }
            }
            error("도달할 수 없는 음성 실행 상태입니다.")
        }
    }

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
    private suspend fun <T> generic(operation: suspend () -> T): T = try {
        withTimeout(timeoutSeconds * 1000) { permits.withPermit { operation() } }
    } catch (_: TimeoutCancellationException) {
        throw LevelTestException("AI_SERVER_UNAVAILABLE", 504, "AI 요청 제한 시간을 초과했습니다.")
    } catch (failure: ModelExecutionFailure) {
        throw LevelTestException(failure.code, failure.status, "AI 실행 요청에 실패했습니다.")
    }

    override fun close() {
        model.close()
        speech.close()
        transcription.close()
    }
}
