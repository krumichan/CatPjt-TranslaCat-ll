package jp.co.translacat.languagelearning.features.writing.domain.model

/** 만료된 생성 lease를 다른 worker가 다시 claim할 때 필요한 식별자만 전달한다. */
internal data class WritingGenerationJob(val setId: Long, val userId: Long)
