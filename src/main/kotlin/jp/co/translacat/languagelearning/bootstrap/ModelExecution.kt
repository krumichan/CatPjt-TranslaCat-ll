package jp.co.translacat.languagelearning.bootstrap

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import jp.co.translacat.languagelearning.shared.ai.HttpModelExecution

internal fun Application.configureModelExecution() {
    fun value(key: String) = environment.config.propertyOrNull(key)?.getString()
    val features = listOf("writing", "practice", "listening", "speaking", "levelTest")
    if (features.none { value("$it.enabled")?.toBooleanStrictOrNull() == true }) return

    // 기능별 활성화 순서와 무관하게 범용 실행 HTTP 클라이언트의 수명을 애플리케이션이 관리한다.
    val aiUrl = value("aiServer.url") ?: error("aiServer.url을 설정해 주세요.")
    val apiKey = value("aiServer.apiKey") ?: error("aiServer.apiKey를 설정해 주세요.")
    dependencies { provide<HttpModelExecution> { HttpModelExecution(aiUrl, apiKey) } }
}
