package jp.co.translacat.languagelearning.features.leveltest.domain.exception

/** 원문 AI 응답·인증정보를 오류 메시지에 포함하지 않는다. */
internal class LevelTestException(val code: String, val httpStatus: Int, message: String) : RuntimeException(message)

internal fun levelInvalid(message: String): Nothing =
    throw LevelTestException("LANGUAGE_LEARNING_LEVEL_TEST_INVALID_STATE", 409, message)

internal fun levelNotFound(): Nothing =
    throw LevelTestException("LANGUAGE_LEARNING_LEVEL_TEST_NOT_FOUND", 404, "레벨 테스트를 찾을 수 없습니다.")
