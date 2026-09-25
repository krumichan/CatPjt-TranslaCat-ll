package jp.co.translacat.languagelearning.features.growth.api

import jp.co.translacat.languagelearning.features.growth.domain.model.*
import kotlinx.serialization.json.*

internal fun JsonObjectBuilder.nullable(name: String, value: Any?) {
    put(name, when (value) {
        null -> JsonNull
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    })
}

internal fun GrowthAcknowledgement.toJson() = buildJsonObject {
    put("sourceInstanceId", sourceInstanceId); put("eventId", eventId); put("userId", userId)
    put("sequence", sequence); put("payloadSha256", payloadSha256); put("outcome", outcome)
}

internal fun GrowthProfile.toJson() = buildJsonObject {
    nullable("profileVersion", profileVersion)
    nullable("state", state)
    nullable("baseLevelScore", baseLevelScore)
    nullable("calibrationStartedDate", calibrationStartedDate)
    nullable("calibrationCompletedDate", calibrationCompletedDate)
    nullable("meaningScore", meaningScore)
    nullable("grammarScore", grammarScore)
    nullable("vocabularyScore", vocabularyScore)
    nullable("naturalnessScore", naturalnessScore)
    nullable("expressionScore", expressionScore)
    nullable("reviewPerformance", reviewPerformance)
    nullable("normalPerformance", normalPerformance)
    nullable("challengePerformance", challengePerformance)
    nullable("evaluationCount", evaluationCount)
    nullable("confidence", confidence)
    nullable("trend", trend)
    nullable("additionalSignalsJson", additionalSignalsJson)
    nullable("baselineCompletionId", baselineCompletionId)
    nullable("baselineCompletedAt", baselineCompletedAt)
 }

internal fun KeywordMastery.toJson() = buildJsonObject {
    put("canonicalKey", canonicalKey); put("score", score); put("evaluationCount", evaluationCount)
    put("selectedCount", selectedCount); nullable("lastSelectedDate", lastSelectedDate)
}

internal fun GrowthSignal.toJson() = buildJsonObject {
    put("key", key); put("occurrenceCount", occurrenceCount)
}

internal fun GrowthActivity.toJson(metrics: List<GrowthMetric>) = buildJsonObject {
    nullable("id", id)
    nullable("source", source)
    nullable("referenceId", referenceId)
    nullable("learningDate", learningDate)
    nullable("title", title)
    nullable("durationSeconds", durationSeconds)
    nullable("status", status)
    nullable("overallScore", overallScore)
    nullable("evaluationConfidence", evaluationConfidence)
    nullable("startedAt", startedAt)
    nullable("completedAt", completedAt)
    nullable("metadataJson", metadataJson)
    put("metrics", JsonArray(metrics.map { it.toJson() }))
}

internal fun GrowthMetric.toJson() = buildJsonObject {
    put("metricType", metricType); put("state", state); nullable("score", score)
    nullable("confidence", confidence); nullable("notEvaluableReason", notEvaluableReason)
}

internal fun GrowthSnapshot.toJson() = buildJsonObject {
    put("userId", userId); put("sourceInstanceId", sourceInstanceId); put("sequence", sequence); put("preview", preview)
    put("profile", profile?.toJson() ?: JsonNull)
    put("masteries", JsonArray(masteries.map { it.toJson() }))
    put("signals", buildJsonObject { signals.forEach { (type, values) -> put(type, JsonArray(values.map { it.toJson() })) } })
}
internal fun GrowthActivityPage.toJson() = buildJsonObject {
    put("userId", userId); put("sourceInstanceId", sourceInstanceId); put("sequence", sequence)
    put("activities", JsonArray(activities.map { (activity, metrics) -> activity.toJson(metrics) }))
    nullable("nextAfterId", nextAfterId)
    put("projectionRevision", projectionRevision)
}
