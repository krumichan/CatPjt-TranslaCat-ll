package jp.co.translacat.languagelearning.features.webaiplan.application

import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningFacts
import jp.co.translacat.languagelearning.features.keyword.application.KeywordOperations
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.webaiplan.domain.model.*

internal data class WebAiContext(
    val originLanguage: String,
    val learningLanguage: String,
    val feedbackLanguage: String,
    val defaultKeywords: List<WebAiKeyword>,
    val catalog: List<WebAiKeyword>,
    val evaluationEnabled: Boolean = true,
)

internal fun interface WebAiContextProvider {
    suspend fun current(userId: Long): WebAiContext
}

internal class SettingsWebAiContextProvider(
    private val settings: SettingsServiceOperations,
    private val keywords: KeywordOperations,
    private val facts: KeywordLearningFacts,
) : WebAiContextProvider {
    override suspend fun current(userId: Long): WebAiContext {
        // 기본 설정의 다음 날 적용·보정 정책을 거친 현재 언어만 사용한다.
        val snapshot = settings.userSnapshot(userId)
        val current = snapshot.result.settings
        val origin = current.originLanguage ?: throw WebAiException("WEB_AI_SETTINGS_REQUIRED", "기본 학습 언어를 먼저 설정해 주세요.", 409)
        val learning = current.learningLanguage ?: throw WebAiException("WEB_AI_SETTINGS_REQUIRED", "기본 학습 언어를 먼저 설정해 주세요.", 409)
        val started = facts.hasStartedLearning(userId)
        val defaults = keywords.candidates(userId, started, snapshot.learningDate)
            .map { WebAiKeyword(it.key, it.text, it.canonicalKey) }
        val list = keywords.list(userId, started, null)
        val catalog = (list.systemKeywords + list.customKeywords).filter { it.active }.map {
            WebAiKeyword("${it.source.name}:${it.id}", it.text, it.canonicalKey)
        }
        return WebAiContext(origin, learning, current.feedbackLanguage ?: origin, defaults, catalog,
            settings.adminPolicy().aiEvaluationEnabled)
    }
}
