package jp.co.translacat.languagelearning.features.resultjournal.application

import jp.co.translacat.languagelearning.features.resultjournal.domain.repository.ResultJournalRepository

/** learner 잠금·stream 순번·수신 사실은 반드시 같은 LL 트랜잭션으로 처리한다. */
internal interface ResultJournalUnitOfWork {
    suspend fun <T> execute(userId: Long, block: ResultJournalRepository.() -> T): T
}
