package jp.co.translacat.languagelearning.features.speaking.execution

import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingPolicy
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingPracticeMode
import kotlinx.serialization.json.*

/** 프롬프트 원문은 추출한 자산을 사용하고 요청에 결합되는 증거 문맥만 구성한다. */
internal object SpeakingEvaluationPrompt {
    fun build(kind: String, request: JsonObject): String = SpeakingEvaluationContract.prefix(kind) +
        (if (kind == "coaching") coaching(request) else evaluation(request)).toString()

    private fun evaluation(request: JsonObject): JsonObject {
        val users = request.objects("userTurns")
        val bounds = SpeakingEvaluationContract.bounds(request)
        val references = SpeakingEvaluationContract.references(request)
        val last = users.maxOf { it.integer("turnIndex") }
        val payload = request.toMutableMap()

        // 마지막 학습자 발화 이후의 AI 답변은 과제 참조에서 제외하며 직전 과제만 연결한다.
        payload["assistantTurns"] =
            JsonArray(request.objects("assistantTurns").filter { it.integer("turnIndex") < last })
        payload["evaluationTaskBindings"] = JsonArray(
            users.map { turn ->
                buildJsonObject {
                    put("userTurnId", turn.getValue("turnId"))
                    put("referenceAssistantTurnId", references[turn.string("turnId")])
                }
            },
        )
        payload["evidenceContract"] = buildJsonObject {
            put("allowedUserTurnIds", strings(bounds.keys))
            put(
                "timestampBoundsByUserTurn",
                buildJsonObject {
                    bounds.forEach { (id, maximum) ->
                        put(
                            id, buildJsonObject { put("minMs", 0); put("maxMs", maximum) },
                        )
                    }
                },
            )
            put("assistantTurnsAreReferenceContextOnly", true)
            put("unsupportedTimestampsMustBeNull", true)
        }
        payload["pronunciationEvidenceAvailable"] = JsonPrimitive(false)
        payload["evaluationCapabilities"] = capabilities(request)

        // 원본 저장 전사는 유지한다. Provider로 보내는 복사본에서만 유효하지 않은 시간 표식을 제외한다.
        payload["userTurns"] = JsonArray(
            users.map { turn ->
                val maximum = bounds[turn.string("turnId")]
                val segments = turn.objects("segments").map { segment ->
                    val valid = maximum != null && segment.integer("startMs") >= 0 &&
                        segment.integer("startMs") <= segment.integer("endMs") && segment.integer("endMs") <= maximum
                    if (valid) segment else JsonObject(
                        segment.filterKeys { it !in setOf("startMs", "endMs") } +
                            ("timestampUsableForEvidence" to JsonPrimitive(false)),
                    )
                }
                JsonObject(
                    turn + mapOf(
                        "segments" to JsonArray(segments),
                        "assistanceLevel" to JsonPrimitive(SpeakingConversationContract.assistanceLevel(turn)),
                        "transcriptObservation" to buildJsonObject {
                            put("source", "AUTOMATIC_SPEECH_RECOGNITION"); put("verbatimAccuracyVerified", false)
                            put("usableForTextEvaluation", SpeakingEvaluationContract.usable(turn))
                            put("confidenceIsCalibratedAccuracy", false)
                        },
                    ),
                )
            },
        )
        return JsonObject(payload)
    }

    private fun capabilities(request: JsonObject): JsonObject {
        val unavailable = SpeakingEvaluationContract.unavailable(request)
        val users = request.objects("userTurns")
        return buildJsonObject {
            put("policyVersion", SpeakingPolicy.EVIDENCE_VERSION); put("source", SpeakingPolicy.EVIDENCE_SOURCE)
            put("acousticEvidenceConsumed", false); put("verifiedTemporalFluencyEvidenceConsumed", false)
            put("audioReferencesAreNotConsumedByEvaluator", true); put("pronunciationPracticeAllowed", false)
            put("unsupportedMetrics", buildJsonObject { unavailable.forEach { (key, value) -> put(key, value) } })
            put(
                "modelAssessableMetrics",
                strings(SpeakingPolicy.weights.keys.map { it.name }.filter { it !in unavailable }),
            )
            put("evaluationConfidenceScope", "MODEL_ASSESSABLE_METRICS_AND_USABLE_TEXT_EVIDENCE")
            put(
                "textEvidenceTurnIds",
                strings(users.filter(SpeakingEvaluationContract::usable).map { it.string("turnId") }),
            )
            put(
                "uncertainTranscriptTurnIds",
                strings(
                    users.filter {
                        !it.boolean("excludedFromEvaluation") && !SpeakingEvaluationContract.usable(it)
                    }.map { it.string("turnId") },
                ),
            )
            put(
                "meaningInterpretation",
                if (SpeakingEvaluationContract.mode(request) == SpeakingPracticeMode.READ_ALOUD)
                    "STT_OBSERVED_SCRIPT_ACCURACY_NOT_PRONUNCIATION" else "OBSERVED_TASK_FULFILLMENT",
            )
            put("profileSignalsRequireIndependentTextEvidence", true); put("readAloudProfileSignalsAllowed", false)
        }
    }

    private fun coaching(request: JsonObject): JsonObject {
        val references = SpeakingEvaluationContract.references(request)
        val usable = request.objects("userTurns").filter(SpeakingEvaluationContract::usable)
        return buildJsonObject {
            listOf(
                "sessionId", "originLanguage", "learningLanguage", "topic", "goal", "resultPolicyVersion",
                "sourceSnapshotHash",
            )
                .forEach { put(it, request.getValue(it)) }
            put(
                "eligibleLearnerTurns",
                JsonArray(
                    usable.map { turn ->
                        buildJsonObject {
                            listOf("turnId", "turnIndex", "transcript", "sttConfidence", "recordingRevision")
                                .forEach { put(it, turn.getValue(it)) }
                            put("referenceAssistantTurnId", references[turn.string("turnId")])
                            put("assistanceUsage", turn.getValue("assistanceUsage"))
                            put("sourceProvenance", "AUTOMATIC_SPEECH_RECOGNITION")
                            put("verbatimAccuracyVerified", false)
                        }
                    },
                ),
            )
            put(
                "evidenceContract",
                buildJsonObject {
                    put("allowedTurnIds", strings(usable.map { it.string("turnId") }))
                    put("sourceExcerptMustBeExactContiguousTranscriptSpan", true)
                    put("assistantAndSuggestedTextAreNotLearnerEvidence", true)
                    put("maxItems", 3)
                },
            )
        }
    }
}
