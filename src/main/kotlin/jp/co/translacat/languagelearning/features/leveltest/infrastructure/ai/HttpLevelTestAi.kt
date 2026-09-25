package jp.co.translacat.languagelearning.features.leveltest.infrastructure.ai

import jp.co.translacat.languagelearning.features.leveltest.application.LevelAudioUpload
import jp.co.translacat.languagelearning.features.leveltest.application.LevelGenerationContext
import jp.co.translacat.languagelearning.features.leveltest.application.LevelTestAi
import jp.co.translacat.languagelearning.features.leveltest.application.LevelTestContext
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.Duration
import java.util.*

/** 기존 FastAPI 경로와 X-API-KEY 계약을 사용한다. 생성/평가 결과는 별도의 도메인 검증을 통과해야 한다. */
internal class HttpLevelTestAi(
    baseUrl: String, private val apiKey: String, private val timeoutSeconds: Long, concurrency: Int = 2,
) : LevelTestAi, AutoCloseable {
    private val http =
        BoundedLevelHttpTransport(baseUrl, Duration.ofSeconds(timeoutSeconds), LevelTestRules.MAX_AUDIO_BYTES)
    private val permits = Semaphore(concurrency)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

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
                buildJsonObject {
                    put("currentSession", histories(ctx.currentItems, ctx)); put(
                    "sameFeatureRecent", histories(ctx.recentItems, ctx),
                )
                    // 다른 기능은 아직 Core에 있으므로 그 이력을 읽었다고 가장하지 않는다.
                    put("crossFeatureRecent", JsonArray(emptyList()))
                    put(
                        "exactContentHashes90d",
                        strings(ctx.recentItems.map { it.data.diversityMetadata.contentHash }.distinct()),
                    )
                },
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
        return decode(post("api/v1/language-learning/level-test/questions/generate", payload), "AI_GENERATION_FAILED")
    }

    override suspend fun evaluate(
        session: LevelSession, item: LevelItem, response: LevelSubmission, audio: ByteArray?,
    ): LevelEvaluationData {
        val key = "lt:${session.uid}:eval:${item.id}:v${response.revision}:r${response.manualRetryCount}"
        val ref = item.data.referencePayload
        val speaking = item.data.answerMode == LevelTestAnswerMode.AUDIO
        val payload = buildJsonObject {
            put("requestId", key); put("idempotencyKey", key); put("sessionId", session.id); put("itemId", item.id)
            put("itemType", item.data.itemType.name); put("promptText", item.data.promptText)
            put("originLanguage", session.originLanguage); put("learningLanguage", session.learningLanguage); put(
            "complexityBand", item.data.complexityBand,
        )
            put("manualRetryAttempt", response.manualRetryCount)
            listOf("providedFacts", "requiredIntents", "responseConstraints").forEach {
                put(
                    it, ref[it] as? JsonArray ?: JsonArray(emptyList()),
                )
            }
            if (speaking) {
                put("referenceText", ref["referenceText"] ?: JsonNull); put(
                    "maxDurationSeconds", (item.data.maxAudioSeconds ?: 30).coerceAtLeast(3),
                )
                put("phraseHints", ref["phraseHints"] as? JsonArray ?: JsonArray(emptyList()))
            } else {
                put("domain", item.data.domain.name); put(
                    "answer", response.textAnswer?.let(::JsonPrimitive) ?: JsonNull,
                )
                put("sourceText", ref["sourceText"] ?: JsonNull); put(
                    "translationSourceText", ref["translationSourceText"] ?: JsonNull,
                )
                put("referenceMeanings", ref["referenceMeanings"] as? JsonArray ?: JsonArray(emptyList()))
                put("keyMeaningUnits", ref["keyMeaningUnits"] as? JsonArray ?: JsonArray(emptyList())); put(
                    "focusMetrics", JsonArray(emptyList()),
                )
            }
        }
        val bytes = if (speaking) {
            require(audio != null && audio.isNotEmpty())
            val boundary = "ll-${UUID.randomUUID()}"
            val out = ByteArrayOutputStream()
            fun line(value: String) {
                out.write(value.toByteArray(Charsets.UTF_8))
            }
            line(
                "--$boundary\r\nContent-Disposition: form-data; name=\"context\"\r\nContent-Type: application/json\r\n\r\n",
            )
            line(payload.toString())
            line(
                "\r\n--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"answer.bin\"\r\nContent-Type: ${response.audioContentType}\r\n\r\n",
            )
            out.write(audio); line("\r\n--$boundary--\r\n")
            request(
                "POST", "api/v1/language-learning/level-test/evaluate/speaking", out.toByteArray(),
                "multipart/form-data; boundary=$boundary",
            ).body
        } else post("api/v1/language-learning/level-test/evaluate/text", payload)
        return decode(bytes, "AI_EVALUATION_FAILED")
    }

    override suspend fun synthesize(
        session: LevelSession, item: LevelItem, text: String, policy: LevelTestContext,
    ): LevelAudioBytes {
        val key = "lt:${session.uid}:model:${item.id}"
        val payload = buildJsonObject {
            put("requestId", key); put("idempotencyKey", key); put("itemId", item.id); put("sourceText", text)
            put(
                "contentHash",
                LevelTestRules.sha256(
                    Normalizer.normalize(text, Normalizer.Form.NFKC).trim().toByteArray(Charsets.UTF_8),
                ),
            )
            put("generationVersion", item.data.generationVersion); put("learningLanguage", session.learningLanguage)
            put(
                "voice",
                buildJsonObject {
                    put("language", session.learningLanguage); put("voiceKey", "marin"); put(
                    "version", "current",
                ); put("quality", "STANDARD")
                },
            )
            put("playbackSpeed", "NORMAL"); put("policyVersion", policy.profilePolicyVersion); put(
            "modelConfigVersion", policy.modelConfigVersion,
        )
            put("automaticRetryLimit", policy.automaticRetryLimit); put("manualRetryAttempt", 0)
        }
        val result = decode<JsonObject>(post("api/v1/language-learning/listening/tts", payload), "AI_TTS_FAILED")
        val reference = (result["audio"] as? JsonObject)?.get("audioReference")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= 500 } ?: throw LevelTestException(
            "AI_TTS_FAILED", 502, "TTS 음성 참조가 없습니다.",
        )
        val encoded = URLEncoder.encode(reference, StandardCharsets.UTF_8).replace("+", "%20")
        val response = request("GET", "api/v1/language-learning/listening/audio/$encoded", null, null)
        return LevelAudioBytes(response.body, response.contentType.substringBefore(';'))
    }

    private fun histories(items: List<LevelItem>, context: LevelGenerationContext) = JsonArray(
        items.takeLast(100).map { item ->
            val m = item.data.diversityMetadata
            buildJsonObject {
                put("sourceType", "LEVEL_TEST"); put("content", item.data.promptText); put(
                "contentHash", m.contentHash,
            ); put("scenarioCategory", m.scenarioCategory)
                put("communicativeIntent", m.communicativeIntent?.let(::JsonPrimitive) ?: JsonNull)
                put("taskArchetype", m.taskArchetype?.let(::JsonPrimitive) ?: JsonNull); put(
                "grammarFocusCodes", strings(m.grammarFocusCodes),
            )
                put("semanticSummary", m.semanticSummary?.let(::JsonPrimitive) ?: JsonNull)
                put("ageDays", Duration.between(item.createdAt, context.nowUtc).toDays().coerceAtLeast(0).toInt())
            }
        },
    )

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
    private suspend fun post(path: String, body: JsonObject): ByteArray =
        request("POST", path, body.toString().toByteArray(Charsets.UTF_8), "application/json").body

    private suspend fun request(
        method: String, path: String, body: ByteArray?, mime: String?,
    ): BoundedLevelHttpTransport.Response {
        try {
            return withTimeout(timeoutSeconds * 1000) {
                permits.withPermit {
                    val response = runInterruptible(Dispatchers.IO) {
                        http.request(method, path, mapOf("X-API-KEY" to apiKey), body, mime)
                    }
                    if (response.status !in 200..299) {
                        throw LevelTestException(
                            "AI_SERVER_UNAVAILABLE", if (response.status == 429) 503 else 502,
                            "AI 서버 요청이 실패했습니다. 상태=${response.status}",
                        )
                    }
                    response
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw LevelTestException("AI_SERVER_UNAVAILABLE", 504, "AI 요청 제한 시간을 초과했습니다.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: LevelTestException) {
            throw known
        } catch (_: Exception) {
            throw LevelTestException("AI_SERVER_UNAVAILABLE", 502, "AI 서버에 연결하거나 응답을 읽지 못했습니다.")
        }
    }

    private inline fun <reified T> decode(bytes: ByteArray, code: String): T = try {
        json.decodeFromString<T>(bytes.toString(Charsets.UTF_8))
    } catch (_: Exception) {
        throw LevelTestException(code, 502, "AI 응답 JSON 계약을 확인해 주세요.")
    }

    override fun close() = http.close()
}
