package jp.co.translacat.languagelearning.features.growth.application

import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository

internal interface GrowthTransaction {
    val records: GrowthRepository
    fun requireActiveIfPresent(userId: Long)
}

internal interface GrowthUnitOfWork {
    suspend fun <T> write(userId: Long, block: GrowthTransaction.() -> T): T
    suspend fun <T> read(block: GrowthTransaction.() -> T): T
}
