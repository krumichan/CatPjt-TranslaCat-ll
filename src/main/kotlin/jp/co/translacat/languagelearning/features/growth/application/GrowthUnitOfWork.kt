package jp.co.translacat.languagelearning.features.growth.application

import jp.co.translacat.languagelearning.features.growth.domain.model.*
import jp.co.translacat.languagelearning.features.growth.domain.repository.GrowthRepository

internal interface GrowthTransaction {
    val records: GrowthRepository
    fun requireActiveIfPresent(userId: Long)
    fun lastSequence(sourceId: String, userId: Long): Long
    fun receipt(eventId: String): GrowthReceipt?
    fun operationHash(sourceId: String, userId: Long, key: String): String?
    fun rememberOperation(sourceId: String, userId: Long, operation: GrowthOperation)
    fun recordReceipt(event: GrowthEvent)
    fun advance(sourceId: String, userId: Long, sequence: Long)
}

internal interface GrowthUnitOfWork {
    suspend fun <T> write(userId: Long, block: GrowthTransaction.() -> T): T
    suspend fun <T> read(block: GrowthTransaction.() -> T): T
}
