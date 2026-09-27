package jp.co.translacat.languagelearning.features.writing.domain.model

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
)

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
