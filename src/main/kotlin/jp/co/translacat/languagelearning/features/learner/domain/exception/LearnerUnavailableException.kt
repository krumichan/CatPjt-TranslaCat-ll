package jp.co.translacat.languagelearning.features.learner.domain.exception

/** 비활성 또는 알 수 없는 학습자 상태의 접근을 거부한다. */
internal class LearnerUnavailableException(userId: Long) :
    IllegalStateException("활성 상태의 학습자가 아닙니다. userId=$userId")
