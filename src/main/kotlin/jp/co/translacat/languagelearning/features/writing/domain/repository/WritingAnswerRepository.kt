package jp.co.translacat.languagelearning.features.writing.domain.repository

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingAnswer
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingAttemptView
import java.time.LocalDate
import java.time.LocalDateTime

internal interface WritingAnswerRepository {
    fun history(userId: Long, itemId: Long): List<WritingAttemptView>
    fun find(userId: Long, itemId: Long, date: LocalDate): WritingAnswer?
    fun findById(userId: Long, answerId: Long): WritingAnswer?
    fun submit(
        userId: Long, itemId: Long, date: LocalDate, text: String, nowUtc: LocalDateTime, old: WritingAnswer?,
    ): WritingAnswer
}
