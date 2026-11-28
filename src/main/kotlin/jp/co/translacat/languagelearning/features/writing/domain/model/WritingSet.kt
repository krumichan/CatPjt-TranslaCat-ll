package jp.co.translacat.languagelearning.features.writing.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.LocalDateTime

internal enum class WritingType { TRANSLATION, GUIDED, FREE }
internal enum class WritingSetStatus { GENERATING, PARTIAL, READY, COMPLETED, FAILED }

/** Core의 기존 daily set 상태 의미를 보존하는 LL 소유 상태. */
internal data class WritingSet(
    val id: Long,
    val userId: Long,
    val learningDate: LocalDate,
    val writingType: WritingType,
    val snapshotId: String,
    val sentenceCount: Int,
    val status: WritingSetStatus,
    val snapshotJson: String,
    val promptVersion: String?,
    val regenerationCount: Int,
    val generationToken: String?,
    val generationLeaseUntil: LocalDateTime?,
    val failureMessage: String?,
) {
    /** 예약 분량에는 이월과 이틀 미학습 정지 당일까지의 풀이 기회를 보존한다. */
    fun lastReviewDate(configuredDays: Int): LocalDate {
        val snapshot = runCatching { Json.parseToJsonElement(snapshotJson) as? JsonObject }.getOrNull()
        val days = if (snapshot?.containsKey("autoPracticeJobId") == true) configuredDays.coerceAtLeast(3)
        else configuredDays
        return learningDate.plusDays(days - 1L)
    }
}

internal data class NewWritingSet(
    val userId: Long,
    val learningDate: LocalDate,
    val writingType: WritingType,
    val snapshotId: String,
    val sentenceCount: Int,
    val snapshotJson: String,
) {
    init {
        require(userId > 0)
        require(snapshotId.isNotBlank() && snapshotId.length <= 100)
        require(sentenceCount > 0)
        require(snapshotJson.isNotBlank())
    }
}
