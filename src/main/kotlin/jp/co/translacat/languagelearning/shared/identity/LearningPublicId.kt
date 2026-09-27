package jp.co.translacat.languagelearning.shared.identity

/** 브라우저에서 정밀도를 유지하는 현재 공개 ID 계약이다. 저장된 양수 PK는 유지한다. */
object LearningPublicId {
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    fun encode(databaseId: Long): Long {
        require(databaseId in 1..MAX_SAFE_INTEGER)
        return -databaseId
    }

    fun decode(raw: String?): Long {
        val value = raw?.toLongOrNull() ?: throw InvalidLearningPublicId()
        if (value !in -MAX_SAFE_INTEGER..-1L) throw InvalidLearningPublicId()
        return -value
    }
}

class InvalidLearningPublicId : RuntimeException("LEARNING_ID_INVALID")
