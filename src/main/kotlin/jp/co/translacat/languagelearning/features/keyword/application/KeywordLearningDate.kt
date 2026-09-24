package jp.co.translacat.languagelearning.features.keyword.application

import java.time.LocalDate

/** 현재 Settings 정책에 따른 날짜다. DB 트랜잭션 밖에서 해당 기능의 포트를 호출한다. */
internal fun interface KeywordLearningDate {
    suspend fun today(userId: Long): LocalDate
}
