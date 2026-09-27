package jp.co.translacat.languagelearning.features.keyword.application

/** 키워드 예약 적용에 필요한 학습 시작 사실은 LL 저장소가 소유한다. */
internal fun interface KeywordLearningFacts {
    suspend fun hasStartedLearning(userId: Long): Boolean
}
