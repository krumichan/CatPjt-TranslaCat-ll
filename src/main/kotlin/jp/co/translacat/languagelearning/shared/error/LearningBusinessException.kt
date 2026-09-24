package jp.co.translacat.languagelearning.shared.error

/** 업무 오류만 공개한다. SQL/연결 정보가 들어 있는 예외 메시지는 응답에 사용하지 않는다. */
internal class LearningBusinessException(val code: String, message: String) : RuntimeException(message)
