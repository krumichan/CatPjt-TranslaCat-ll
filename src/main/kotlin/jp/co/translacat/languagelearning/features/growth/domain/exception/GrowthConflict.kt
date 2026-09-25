package jp.co.translacat.languagelearning.features.growth.domain.exception

internal class GrowthConflict(val code: String) : RuntimeException(code)
internal class GrowthPending(val required: Long, val applied: Long) : RuntimeException("GROWTH_SYNC_PENDING")
