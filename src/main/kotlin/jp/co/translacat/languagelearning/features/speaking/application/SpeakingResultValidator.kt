package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingJobRecord
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingResultKind
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** 기존 Core의 최종 저장 검사를 제출 시점의 고정 요청에 적용한다. */
internal object SpeakingResultValidator {
    private val axes = setOf(
        "GRAMMAR", "VOCABULARY", "NATURALNESS", "MEANING", "EXPRESSIVENESS", "FLUENCY", "PRONUNCIATION", "INTERACTION",
    )

    fun validate(job: SpeakingJobRecord, response: JsonObject) {
        val request = job.request
        require(request["requestId"] == response["requestId"] && request["sessionId"] == response["sessionId"])
        if (job.resultKind == SpeakingResultKind.SESSION_COACHING) coaching(job, response)
        else scored(request, response)
    }

    private fun scored(request: JsonObject, response: JsonObject) {
        // 상태·버전·종합 점수부터 검사하며 사전검사 실패의 빈 metric 응답은 별도 합법 경로다.
        val status = response.text("status")?.uppercase()
        require(status in setOf("EVALUATED", "INSUFFICIENT_EVIDENCE"))
        listOf("evaluationVersion", "scoringPolicyVersion", "promptVersion").forEach {
            require(response.text(it)?.let { value -> value.isNotBlank() && value.length <= 100 } == true)
        }
        confidence(response["evaluationConfidence"])
        val metrics = response["metrics"] as? JsonArray
        val eligibility = response["eligibility"] as? JsonObject
        val evaluated = status == "EVALUATED"
        if (evaluated) {
            score(response["overallScore"])
            require(response.number("evaluationConfidence") != null)
        } else {
            require(response["overallScore"].missing())
            require(response.rows("profileSignals").isEmpty())
        }
        val precheck = !evaluated && metrics != null && metrics.isEmpty()
        if (precheck) {
            require(response["evaluationConfidence"].missing() && eligibility != null)
            require(eligibility["eligibleBeforeAi"] == JsonPrimitive(false))
            require(eligibility.rows("missingRequirements").isNotEmpty())
        } else {
            require(metrics != null && metrics.size == axes.size)
            val turns = request.rows("userTurns").map { it.jsonObject }
                .filter { it["excludedFromEvaluation"] != JsonPrimitive(true) }
                .mapNotNull { it.text("turnId") }.toSet()
            val found = mutableSetOf<String>()
            metrics.forEach { entry ->
                val metric = entry.jsonObject
                require(metric.text("type")?.let { it in axes && found.add(it) } == true)
                confidence(metric["confidence"])
                when (metric.text("state")?.uppercase()) {
                    "NOT_EVALUABLE" -> require(metric["score"].missing())
                    "EVALUATED" -> score(metric["score"])
                    else -> error("SPEAKING_METRIC_STATE_INVALID")
                }
                metric.rows("evidence").forEach { evidence ->
                    (evidence as? JsonObject)?.text("turnId")?.let { require(it in turns) }
                }
            }
            require(found == axes)
            require(!evaluated || metrics.any { it.jsonObject.text("state").equals("EVALUATED", true) })
        }
        if (eligibility != null) {
            require(checkNotNull(eligibility.number("validUserTurns")) >= 0)
            require(checkNotNull(eligibility.number("validUserSpeechSeconds")).let { it.isFinite() && it >= 0 })
            confidence(eligibility["validSttTurnRatio"])
        }

        // evidence-v2는 기존 요청의 정책을 기준으로 강제하며 누락을 구 정책으로 내려 해석하지 않는다.
        val evidenceVersion = response.text("evidencePolicyVersion")
        if (evidenceVersion == null) {
            require(request.text("evaluationPolicyVersion") != "speaking-evaluation-policy-v2")
            return
        }
        require(evidenceVersion == "speaking-transcript-evidence-v2")
        require(response.text("evidenceSource") == "TRANSCRIPT_OBSERVATION")
        val assessed = checkNotNull(metrics).map { it.jsonObject }.filter { it.text("state") == "EVALUATED" }
        val assessedAxes = assessed.map { checkNotNull(it.text("type")) }.toSet()
        val reportedAxes = response["evaluatedAxes"] as? JsonArray
        require(reportedAxes != null && reportedAxes.size == assessedAxes.size)
        require(reportedAxes.map { it.jsonPrimitive.content }.toSet() == assessedAxes)
        val coverage = assessedAxes.sumOf {
            when (it) {
                "FLUENCY" -> .20; "PRONUNCIATION", "INTERACTION" -> .15; else -> .10
            }
        }
        require(
            response.number("evaluationCoverage")
                ?.let { it.isFinite() && kotlin.math.abs(it - coverage) <= .0001 } == true,
        )
        require("PRONUNCIATION" !in assessedAxes && "FLUENCY" !in assessedAxes)
        require(response.rows("pronunciationPractice").isEmpty())
        if (request.text("practiceMode") == "READ_ALOUD") {
            require(assessedAxes.all { it == "MEANING" } && response.rows("profileSignals").isEmpty())
        }

        // 저장할 metric과 profile은 신뢰 가능한 실제 발화 ID를 참조해야 한다.
        val usable = request.rows("userTurns").map { it.jsonObject }.filter { turn ->
            turn["excludedFromEvaluation"] != JsonPrimitive(true) && !turn.text("transcript").isNullOrBlank() &&
                (turn.number("sttConfidence") ?: 0.0) >= .55 &&
                turn.rows("segments").all { (it.jsonObject.number("confidence") ?: 0.0) >= .55 }
        }.mapNotNull { it.text("turnId") }.toSet()
        assessed.forEach { metric ->
            val evidence = metric.rows("evidence")
            require(evidence.isNotEmpty() && evidence.all { it.jsonObject.text("turnId") in usable })
        }
        response.rows("profileSignals").forEach { entry ->
            val signal = entry.jsonObject
            val ids = signal.rows("evidenceTurnIds").map { it.jsonPrimitive.content }
            require(signal.text("metricType") in assessedAxes && ids.toSet().size >= 2 && usable.containsAll(ids))
        }
    }

    private fun coaching(job: SpeakingJobRecord, response: JsonObject) {
        // 코칭 정책·snapshot 결합과 실제 인식 발화의 revision/hash를 저장 직전에 다시 확인한다.
        val request = job.request
        require(request.text("resultKind") == "SESSION_COACHING" && response.text("resultKind") == "SESSION_COACHING")
        require(request.text("resultPolicyVersion") == job.resultPolicyVersion)
        require(response["resultPolicyVersion"] == request["resultPolicyVersion"])
        require(response["sourceSnapshotHash"] == request["sourceSnapshotHash"])
        require(response.text("sourceSnapshotHash") == job.sourceSnapshotHash)
        val items = response["items"] as? JsonArray
        require(items != null && items.size <= 3)
        val turns = request.rows("userTurns").map { it.jsonObject }.associateBy { it.text("turnId") }
        val observations = mutableSetOf<String?>()
        items.forEach { entry ->
            val item = entry.jsonObject
            val evidence = item.getValue("evidence").jsonObject
            require(observations.add(item.text("observationId")))
            val turn = checkNotNull(turns[evidence.text("turnId")])
            require(turn["excludedFromEvaluation"] != JsonPrimitive(true))
            require(turn["recordingRevision"] == evidence["recordingRevision"])
            val transcript = checkNotNull(turn.text("transcript"))
            require(transcript.contains(checkNotNull(evidence.text("transcriptExcerpt"))))
            val hash = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            require(hash == evidence.text("transcriptHash"))
            require(evidence.text("sourceProvenance") == "AUTOMATIC_SPEECH_RECOGNITION")
            require(evidence["verbatimAccuracyVerified"] == JsonPrimitive(false))
            require(item["suggestionIsLearnerEvidence"] == JsonPrimitive(false))
        }
        if (response.text("contentStatus") == "GROUNDED") require(items.isNotEmpty())
        if (response.text("contentStatus") == "NO_USABLE_EVIDENCE") require(items.isEmpty())
    }

    private fun score(value: JsonElement?) {
        val number = (value as? JsonPrimitive)?.doubleOrNull
        require(number != null && number.isFinite() && number in 0.0..100.0)
    }

    private fun confidence(value: JsonElement?) {
        if (value.missing()) return
        val number = (value as? JsonPrimitive)?.doubleOrNull
        require(number != null && number.isFinite() && number in 0.0..1.0)
    }

    private fun JsonElement?.missing() = this == null || this == JsonNull
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.rows(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())
}
