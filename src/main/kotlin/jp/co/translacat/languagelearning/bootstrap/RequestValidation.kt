package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.requestvalidation.*

fun Application.configureRequestValidation() {
    // Generator의 Hello 문자열 예제를 제거한다. 업무 검증은 각 기능의 정책에서 수행한다.
    install(RequestValidation)
}
