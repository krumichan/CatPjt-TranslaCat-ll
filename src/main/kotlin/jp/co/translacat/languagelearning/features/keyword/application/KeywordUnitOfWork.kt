package jp.co.translacat.languagelearning.features.keyword.application

import jp.co.translacat.languagelearning.features.keyword.domain.repository.CustomKeywordRepository
import jp.co.translacat.languagelearning.features.keyword.domain.repository.SystemKeywordLocaleRepository
import jp.co.translacat.languagelearning.features.keyword.domain.repository.SystemKeywordRepository
import jp.co.translacat.languagelearning.features.keyword.domain.repository.SystemKeywordSelectionRepository
import java.time.LocalDateTime

internal interface KeywordTransaction {
    val system: SystemKeywordRepository
    val custom: CustomKeywordRepository
    val selections: SystemKeywordSelectionRepository
    val locales: SystemKeywordLocaleRepository
    val nowUtc: LocalDateTime
}

internal interface KeywordUnitOfWork {
    // learnerId=null은 관리자 카탈로그 작업이다. 사용자 작업은 해당 learner도 잠근다.
    suspend fun <T> execute(learnerId: Long?, block: KeywordTransaction.() -> T): T
}
