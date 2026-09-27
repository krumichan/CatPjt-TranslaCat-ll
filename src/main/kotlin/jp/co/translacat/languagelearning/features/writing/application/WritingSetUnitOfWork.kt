package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository
import jp.co.translacat.languagelearning.features.writing.domain.repository.*
import java.time.LocalDateTime

internal interface WritingSetTransaction {
    val sets: WritingSetRepository
    val items: WritingItemRepository
    val answers: WritingAnswerRepository
    val evaluations: WritingEvaluationRepository
    val fingerprints: WritingFingerprintRepository
    val growth: GrowthRepository
    val nowUtc: LocalDateTime
}

internal interface WritingSetUnitOfWork {
    suspend fun <T> write(userId: Long, block: WritingSetTransaction.() -> T): T
    suspend fun <T> read(block: WritingSetTransaction.() -> T): T
}
