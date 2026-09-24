package jp.co.translacat.languagelearning.features.keyword.application

import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordCandidate
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordChange
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordList
import jp.co.translacat.languagelearning.features.keyword.domain.model.KeywordView
import java.time.LocalDate

internal interface KeywordOperations {
    suspend fun list(userId: Long, started: Boolean, locale: String?): KeywordList
    suspend fun createCustom(userId: Long, started: Boolean, change: KeywordChange): KeywordView
    suspend fun updateCustom(userId: Long, started: Boolean, keywordId: Long, change: KeywordChange): KeywordView
    suspend fun deleteCustom(userId: Long, started: Boolean, keywordId: Long)
    suspend fun selectSystem(userId: Long, started: Boolean, keywordId: Long, selected: Boolean): KeywordView
    suspend fun listSystem(): List<KeywordView>
    suspend fun createSystem(actorId: Long, change: KeywordChange): KeywordView
    suspend fun updateSystem(actorId: Long, keywordId: Long, change: KeywordChange): KeywordView
    suspend fun candidates(userId: Long, started: Boolean, date: LocalDate): List<KeywordCandidate>
}
