package jp.co.translacat.languagelearning.features.overview.application

import jp.co.translacat.languagelearning.features.webaiplan.domain.model.WebAiResult
import jp.co.translacat.languagelearning.features.webaiplan.domain.model.WebAiVerifiedItem
import kotlinx.serialization.json.*
import java.time.LocalDate

/** 표시 출처는 WEB_AI로 남기고, 내부 검증된 지표는 해당 학습 분야의 기존 추세에 합친다. */
internal object WebAiOverviewProjection {
    fun facts(results: List<WebAiResult>, from: LocalDate, to: LocalDate): List<JsonObject> = results
        .filter { LocalDate.parse(it.learningDate) in from..to }
        .sortedByDescending { it.updatedAt }
        .flatMap { result ->
            result.items.filter { it.status == "VERIFIED" && it.officialScore != null }.map { verified ->
                buildJsonObject {
                    put("domain", verified.item.areaCode.substringBefore('_'))
                    put("learningDate", result.learningDate)
                    put("evaluatedAt", result.updatedAt)
                    put("overallScore", verified.officialScore)
                    put("resultId", result.id)
                    put("itemId", verified.item.itemId)
                    put("metrics", JsonArray(verified.metrics.map { metric ->
                        buildJsonObject { put("type", metric.code); put("score", metric.value) }
                    }))
                }
            }
        }

    fun history(result: WebAiResult): JsonObject {
        // 외부 점수는 목록 평균에 넣지 않는다. 미평가·코칭 결과는 점수가 없는 이력으로 표시한다.
        val scores = result.items.filter { it.status == "VERIFIED" }.mapNotNull { it.officialScore }
        val domains = result.items.map { it.item.areaCode.substringBefore('_') }.distinct()
        return buildJsonObject {
            put("activityId", "WEB_AI:${result.id}")
            put("source", "WEB_AI")
            put("learningDate", result.learningDate)
            put("title", result.planName)
            put("topic", domains.joinToString(" · "))
            // 외부 환경에서 소요한 시간은 검증하지 못하므로 활동 시간을 추정하지 않는다.
            put("durationSeconds", 0)
            put("overallScore", if (scores.isEmpty()) null else round2(scores.average()))
            put("completionStatus", "COMPLETED")
            put("evaluationStatus", result.status)
        }
    }

    fun completedItems(results: List<WebAiResult>, domain: String, date: LocalDate): Int = results
        .filter { it.learningDate == date.toString() }
        .sumOf { result -> result.items.count { completed(it) && it.item.areaCode.startsWith("${domain}_") } }

    fun completedDates(results: List<WebAiResult>): Set<LocalDate> = results
        .filter { it.items.any(::completed) }
        .map { LocalDate.parse(it.learningDate) }
        .toSet()

    fun includesDomain(result: WebAiResult, domain: String) =
        result.items.any { it.item.areaCode.startsWith("${domain}_") }

    private fun completed(item: WebAiVerifiedItem) =
        item.item.answerText.isNotBlank() && item.status in setOf("VERIFIED", "COACHING", "REJECTED")
}
