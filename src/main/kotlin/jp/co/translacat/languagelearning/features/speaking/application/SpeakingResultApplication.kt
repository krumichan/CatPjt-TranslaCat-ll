package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.growth.application.GrowthProjector
import jp.co.translacat.languagelearning.features.growth.domain.model.EvidenceFact
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthActivity
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthChange
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthMetric
import jp.co.translacat.languagelearning.features.speaking.domain.*
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import kotlinx.serialization.json.*

/** 원본 결과·활동·공식 지표·job 완료를 같은 LL 트랜잭션 안에서 확정한다. */
internal class SpeakingResultApplication(private val work: SpeakingUnitOfWork) {
    suspend fun complete(claim: SpeakingEvaluationClaim, response: JsonObject): Boolean =
        SpeakingEvaluationState(work).complete(
            claim, response, SpeakingResultValidator::validate,
        ) { session, job, result ->
            require(
                session.snapshot.resultKind == job.resultKind && session.snapshot.resultPolicyVersion == job.resultPolicyVersion,
            )
            when {
                job.resultKind == SpeakingResultKind.SESSION_COACHING -> applyCoaching(session, job, result)
                job.problemIndex > 0 -> applyProblem(session, job, result)
                else -> applySession(session, result)
            }
            (result["usage"] as? JsonObject)?.let {
                records.addUsage(
                    session.id, null, it, job.manualRetryCount, nowUtc,
                )
            }
        }

    suspend fun evaluation(userId: Long, sessionId: Long): JsonObject? = work.read {
        val session = records.session(userId, sessionId)?.takeIf { it.openingReady }
            ?: throw SpeakingFailure("SESSION_NOT_FOUND")
        if (session.snapshot.resultKind != SpeakingResultKind.SCORED_EVALUATION) return@read null
        val result = records.result(session.id, 0)
        if (result == null && session.evaluationStatus == SpeakingEvaluationStatus.PENDING) {
            throw SpeakingFailure("EVALUATION_PENDING")
        }
        result?.let { evaluationView(it) }
    }

    private fun SpeakingTransaction.applyCoaching(
        session: SpeakingSessionRecord, job: SpeakingJobRecord, response: JsonObject,
    ) {
        // FREE 코칭은 공식 평가 상태·metric·band·profile evidence를 만들지 않는다.
        val existing = records.result(session.id, 0)
        if (existing != null) {
            require(existing.response["sourceSnapshotHash"] == job.request["sourceSnapshotHash"])
            return
        }
        records.saveResult(
            SpeakingResultRecord(
                session.id, 0, SpeakingResultKind.SESSION_COACHING,
                checkNotNull(response.text("contentStatus")), response, nowUtc,
            ),
        )
    }

    private fun SpeakingTransaction.applyProblem(
        session: SpeakingSessionRecord, job: SpeakingJobRecord, response: JsonObject,
    ) {
        // 문제별 점수는 원본처럼 문제 결과에만 저장한다. SESSION job의 공식 성장과 합산하지 않는다.
        val previous = checkNotNull(records.result(session.id, job.problemIndex))
        val details = JsonObject(
            previous.response + response + buildJsonObject {
                put("errorMessage", JsonNull)
                put("evaluatedAt", nowUtc.toString())
            },
        )
        records.saveResult(
            previous.copy(
                status = checkNotNull(response.text("status")).uppercase(), response = details, updatedAt = nowUtc,
            ),
        )
    }

    private fun SpeakingTransaction.applySession(session: SpeakingSessionRecord, response: JsonObject) {
        // AI 버전이 달라져도 이미 확정한 원본 결과를 다시 반영하지 않는다.
        val previous = records.result(session.id, 0)
        if (previous != null) {
            markSessionResult(session, previous.status, previous.response.text("evaluationVersion"))
            return
        }
        val confidence = response.number("evaluationConfidence")
        val formal = response.text("status").equals("EVALUATED", true) && confidence != null && confidence >= .70
        val status = if (formal) "EVALUATED" else "INSUFFICIENT_EVIDENCE"
        val stored = JsonObject(
            response + buildJsonObject {
                put("status", status)
                put("overallScore", if (formal) response.getValue("overallScore") else JsonNull)
            },
        )
        records.saveResult(
            SpeakingResultRecord(session.id, 0, SpeakingResultKind.SCORED_EVALUATION, status, stored, nowUtc),
        )
        markSessionResult(session, status, response.text("evaluationVersion"))

        // 원본 도움말 가중치와 유효 STT 비율로 공식 evidence를 반영한다. 낮은 confidence는 원본 결과에 남긴다.
        val usages = records.turns(session.id).flatMap { it.content.assistanceUsage }
        val assistanceWeight = when {
            SpeakingAssistanceType.SAMPLE_ANSWER in usages -> .60
            usages.any { it in setOf(SpeakingAssistanceType.HINT, SpeakingAssistanceType.TRANSLATION) } -> .80
            else -> 1.0
        }
        val validity = (response["eligibility"] as? JsonObject)?.number("validSttTurnRatio")?.coerceAtMost(1.0) ?: 1.0
        val weight =
            if (formal) Math.round(checkNotNull(confidence) * assistanceWeight * validity * 1000) / 1000.0 else 0.0
        val metrics = response.rows("metrics").map { entry ->
            val metric = entry.jsonObject
            GrowthMetric(
                checkNotNull(metric.text("type")),
                if (metric.text("state").equals("NOT_EVALUABLE", true)) "NOT_EVALUABLE" else "EVALUATED",
                metric.number("score"), metric.number("confidence"),
                metric.text("notEvaluableReason")?.takeIf(String::isNotBlank),
            )
        }
        val evidence = response.rows("profileSignals").map { it.jsonObject }
            .filter { !it.text("patternKey").isNullOrBlank() }.map { signal ->
                EvidenceFact(
                    signal.text("metricType"), checkNotNull(signal.text("patternKey")),
                    signal.text("direction")?.takeIf(String::isNotBlank), checkNotNull(signal.number("confidence")),
                    signal.text("recommendedFocus")?.takeIf(String::isNotBlank),
                )
            }
        GrowthProjector(growth).apply(
            session.userId,
            GrowthChange.SpeakingScored(
                resultActivity(session, stored, status), metrics, formal, weight, evidence,
            ),
            nowUtc,
        )
    }

    private fun SpeakingTransaction.markSessionResult(
        session: SpeakingSessionRecord, status: String, version: String?,
    ) {
        // 증거 부족도 정상 종결 상태다. EVALUATED와 점수 상태는 별도로 보존한다.
        records.saveSession(
            session.copy(
                status = SpeakingSessionStatus.EVALUATED,
                evaluationStatus = SpeakingEvaluationStatus.valueOf(status), evaluationVersion = version,
            ),
        )
    }

    private fun SpeakingTransaction.resultActivity(
        session: SpeakingSessionRecord, response: JsonObject, status: String,
    ): GrowthActivity {
        val old = growth.activity(session.userId, "SPEAKING", "ll-speaking-${session.id}")
        val snapshot = session.snapshot
        val metadata = buildJsonObject {
            put("topicCategory", snapshot.topicCategory)
            put("conversationStartMode", snapshot.conversationStartMode.name)
            put("resolvedStartMode", snapshot.resolvedStartMode.name)
            put("correctionMode", snapshot.correctionMode.name)
            put("selectedKeywords", JsonArray(snapshot.selectedKeywords).toString())
            put("evaluationSkipped", false)
            put("resultKind", snapshot.resultKind.name)
            put("resultPolicyVersion", snapshot.resultPolicyVersion)
        }
        return GrowthActivity(
            id = old?.id ?: 0, userId = session.userId, source = "SPEAKING", referenceId = "ll-speaking-${session.id}",
            learningDate = session.learningDate, title = snapshot.topicTitle,
            durationSeconds = session.totalDurationSeconds.coerceAtLeast(0),
            status = status, overallScore = response.number("overallScore"),
            evaluationConfidence = response.number("evaluationConfidence"),
            startedAt = session.startedAt, completedAt = session.completedAt, metadataJson = metadata.toString(),
            createdAt = old?.createdAt ?: nowUtc, updatedAt = nowUtc,
        )
    }

    private fun evaluationView(result: SpeakingResultRecord): JsonObject = buildJsonObject {
        val response = result.response
        put("evaluationId", LearningPublicId.encode(result.id))
        put("sessionId", LearningPublicId.encode(result.sessionId))
        put("status", result.status)
        put("overallScore", response["overallScore"] ?: JsonNull)
        put("evaluationConfidence", response["evaluationConfidence"] ?: JsonNull)
        put(
            "metrics",
            JsonArray(
                response.rows("metrics").map { it.jsonObject }.sortedBy { it.text("type") }.map { metric ->
                    buildJsonObject {
                        put("metricType", metric.getValue("type"))
                        put(
                            "state",
                            if (metric.text("state").equals("NOT_EVALUABLE", true)) "NOT_EVALUABLE" else "EVALUATED",
                        )
                        listOf("score", "confidence", "summary", "notEvaluableReason").forEach {
                            put(
                                it, metric[it] ?: JsonNull,
                            )
                        }
                        put("evidenceJson", (metric["evidence"] ?: JsonNull).toString())
                    }
                },
            ),
        )
        listOf("strengths", "improvements", "recommendedExpressions", "pronunciationPractice").forEach {
            put("${it}Json", (response[it] ?: JsonNull).toString())
        }
        val evidence = buildJsonObject {
            evidenceKeys.forEach { put(it, response[it] ?: JsonNull) }
        }
        val eligibility = response["eligibility"] as? JsonObject ?: JsonObject(emptyMap())
        put("eligibilityJson", JsonObject(eligibility + ("evidenceMetadata" to evidence)).toString())
        listOf("evaluationVersion", "scoringPolicyVersion", "promptVersion").forEach {
            put(
                it, response[it] ?: JsonNull,
            )
        }
        put("evaluatedAt", result.updatedAt.toString())
        evidenceKeys.forEach { put(it, response[it] ?: JsonNull) }
    }

    private val evidenceKeys = listOf("evaluatedAxes", "evaluationCoverage", "evidencePolicyVersion", "evidenceSource")
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.rows(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())
}
