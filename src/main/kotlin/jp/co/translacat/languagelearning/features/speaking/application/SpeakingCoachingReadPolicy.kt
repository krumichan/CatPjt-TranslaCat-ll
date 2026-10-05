package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.speaking.domain.*
import jp.co.translacat.languagelearning.features.speaking.execution.SpeakingEvaluationContract
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** 저장 결과를 재평가하지 않고 현재 원본과 대조하여 더 이상 확인할 수 없는 인용을 숨긴다. */
internal object SpeakingCoachingReadPolicy {
    fun project(
        session: SpeakingSessionRecord, result: SpeakingResultRecord,
        job: SpeakingJobRecord?, currentTurns: List<SpeakingTurnRecord>,
    ): JsonObject {
        val response = result.response
        fun unavailable(reason: String) = projected(response, emptyList(), "UNAVAILABLE", listOf(reason))

        // 정책·세션·저장 요청의 연결부터 확인한다. 알 수 없는 provenance를 정상으로 추측하지 않는다.
        if (session.snapshot.resultPolicyVersion != "free-session-coaching-v1" ||
            response["schemaVersion"] != JsonPrimitive("speaking-session-coaching-schema-v1")
        ) {
            return unavailable("UNKNOWN_RESULT_POLICY")
        }
        if (result.sessionId != session.id || result.resultKind != SpeakingResultKind.SESSION_COACHING ||
            job == null || job.sessionId != session.id || job.resultKind != SpeakingResultKind.SESSION_COACHING ||
            job.resultPolicyVersion != session.snapshot.resultPolicyVersion ||
            job.request["sessionId"] != JsonPrimitive(session.id.toString())
        ) return unavailable("SOURCE_PROVENANCE_UNAVAILABLE")
        if (runCatching { SpeakingResultValidator.validate(job, response) }.isFailure) {
            return unavailable("SOURCE_PROVENANCE_MISMATCH")
        }

        // 기존 저장 validator와 같은 transcript 계약을 사용하며 현재 turn과 도움말·질문 연결도 대조한다.
        val items = (response["items"] as? JsonArray).orEmpty()
        val retained = items.filter { entry ->
            runCatching {
                val evidence = entry.jsonObject.getValue("evidence").jsonObject
                val turnId = evidence.getValue("turnId").jsonPrimitive.content
                val current = currentTurns.singleOrNull { it.id.toString() == turnId && it.sessionId == session.id }
                    ?: return@runCatching false
                val original = job.request.getValue("userTurns").jsonArray.map { it.jsonObject }
                    .singleOrNull { it["turnId"]?.jsonPrimitive?.content == turnId } ?: return@runCatching false
                val transcript = current.content.transcript ?: return@runCatching false
                val usable = SpeakingPolicy.transcriptUsable(SpeakingEvidenceTurn(
                    turnId, transcript, current.content.sttConfidence ?: 0.0,
                    current.content.durationSeconds, current.excludedFromEvaluation,
                    current.content.sttSegments.map { it.getValue("confidence").jsonPrimitive.double },
                ))
                val hash = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                val currentAssistance = current.content.assistanceUsage.groupingBy { it.name }.eachCount()
                usable && current.recordingRevision == evidence["recordingRevision"]?.jsonPrimitive?.intOrNull &&
                    current.turnIndex == evidence["turnIndex"]?.jsonPrimitive?.intOrNull &&
                    current.turnIndex == original["turnIndex"]?.jsonPrimitive?.intOrNull &&
                    hash == evidence["transcriptHash"]?.jsonPrimitive?.contentOrNull &&
                    transcript.contains(evidence.getValue("transcriptExcerpt").jsonPrimitive.content) &&
                    currentAssistance == assistance(original["assistanceUsage"]) &&
                    currentAssistance == assistance(evidence["assistanceUsage"]) &&
                    referenceMatches(session, job.request, evidence, turnId, currentTurns)
            }.getOrDefault(false)
        }
        // 세션 요약은 인용되지 않은 발화도 사용할 수 있으므로 저장된 전체 원본의 일치를 별도로 확인한다.
        val sourceUnchanged = runCatching { sourceMatches(session, job.request, currentTurns) }.getOrDefault(false)
        val availability = when {
            retained.size == items.size && sourceUnchanged -> "AVAILABLE"
            retained.isEmpty() -> "UNAVAILABLE"
            else -> "LIMITED"
        }
        return projected(
            response, retained, availability,
            if (availability == "AVAILABLE") emptyList() else listOf("SOURCE_EVIDENCE_CHANGED_OR_UNAVAILABLE"),
        )
    }

    // 코칭 판정과 독립된 저장 원본의 일치 검사이며 점수형 대화 요약도 같은 원본 계약을 사용한다.
    fun sourceMatches(
        session: SpeakingSessionRecord, request: JsonObject, currentTurns: List<SpeakingTurnRecord>,
    ): Boolean {
        // 제출 당시부터 제외된 원본도 전체 대화 요약의 재노출 근거로 사용하지 않는다.
        if (currentTurns.any { it.excludedFromEvaluation }) return false
        val originals = request.getValue("userTurns").jsonArray.map { it.jsonObject }
        if (originals.size != currentTurns.size) return false
        val originalIds = originals.map { it["turnId"]?.jsonPrimitive?.contentOrNull }
        if (originalIds.toSet().size != originals.size ||
            originalIds.toSet() != currentTurns.map { it.id.toString() }.toSet()
        ) return false
        if (!originals.all { original ->
            val current = currentTurns.singleOrNull {
                it.sessionId == session.id && it.id.toString() == original["turnId"]?.jsonPrimitive?.contentOrNull
            } ?: return@all false
            current.turnIndex == original["turnIndex"]?.jsonPrimitive?.intOrNull &&
                current.recordingRevision == original["recordingRevision"]?.jsonPrimitive?.intOrNull &&
                (current.content.transcript ?: "") == original["transcript"]?.jsonPrimitive?.contentOrNull &&
                current.excludedFromEvaluation == original["excludedFromEvaluation"]?.jsonPrimitive?.booleanOrNull &&
                current.content.assistanceUsage.groupingBy { it.name }.eachCount() == assistance(original["assistanceUsage"])
        }) return false
        return request.getValue("assistantTurns").jsonArray.all { entry ->
            val original = entry.jsonObject
            val id = original.getValue("turnId").jsonPrimitive.content
            val text = if (id == "opening") session.opening["assistantText"]?.jsonPrimitive?.contentOrNull
            else currentTurns.singleOrNull { it.sessionId == session.id && it.id.toString() == id }?.content?.assistantText
            text == original["text"]?.jsonPrimitive?.contentOrNull
        }
    }

    private fun referenceMatches(
        session: SpeakingSessionRecord, request: JsonObject, evidence: JsonObject,
        turnId: String, currentTurns: List<SpeakingTurnRecord>,
    ): Boolean {
        val reference = SpeakingEvaluationContract.references(request)[turnId]
        if ((evidence["referenceAssistantTurnId"] as? JsonPrimitive)?.contentOrNull != reference) return false
        if (reference == null) return true
        val original = request.getValue("assistantTurns").jsonArray.map { it.jsonObject }
            .singleOrNull { it["turnId"]?.jsonPrimitive?.content == reference } ?: return false
        val currentText = if (reference == "opening") session.opening["assistantText"]?.jsonPrimitive?.contentOrNull
        else currentTurns.singleOrNull { it.sessionId == session.id && it.id.toString() == reference }
            ?.content?.assistantText
        return currentText != null && currentText == original["text"]?.jsonPrimitive?.contentOrNull
    }

    private fun assistance(value: JsonElement?): Map<String, Int> =
        (value as? JsonArray).orEmpty().associate {
            val usage = it.jsonObject
            usage.getValue("type").jsonPrimitive.content to usage.getValue("count").jsonPrimitive.int
        }

    private fun projected(
        response: JsonObject, items: List<JsonElement>, availability: String, limitations: List<String>,
    ) = JsonObject(response + mapOf(
        "items" to JsonArray(items),
        "evidenceAvailability" to JsonPrimitive(availability),
        "evidenceLimitations" to JsonArray(limitations.map(::JsonPrimitive)),
    ))
}
