package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.speaking.domain.ConversationStartMode
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingFailure
import jp.co.translacat.languagelearning.features.speaking.domain.SpeakingTopic
import kotlinx.serialization.Serializable

@Serializable
internal data class SpeakingTopicChange(
    val title: String? = null,
    val description: String? = null,
    val recommendedLevel: String? = null,
    val recommendedStartMode: ConversationStartMode? = null,
    val sortOrder: Int? = null,
    val active: Boolean? = null,
)

internal class SpeakingTopicService(private val work: SpeakingUnitOfWork) {
    suspend fun list(language: String?, category: String?, includeInactive: Boolean = false): List<SpeakingTopic> =
        work.read {
            val specifiedLanguage = language?.trim()?.takeIf(String::isNotBlank)
            records.topics().filter { topic ->
                (includeInactive || topic.active) && (specifiedLanguage == null || topic.learningLanguage.equals(
                    specifiedLanguage, true,
                )) &&
                    (category == null || topic.category == category)
            }
        }

    suspend fun update(topicId: Long, change: SpeakingTopicChange): SpeakingTopic = work.catalogWrite {
        val topic = records.topic(topicId) ?: throw SpeakingFailure("SPEAKING_TOPIC_NOT_FOUND")
        records.saveTopic(
            topic.copy(
                title = change.title?.takeIf(String::isNotBlank)?.trim() ?: topic.title,
                description = change.description?.trim() ?: topic.description,
                recommendedLevel = change.recommendedLevel?.trim() ?: topic.recommendedLevel,
                recommendedStartMode = change.recommendedStartMode ?: topic.recommendedStartMode,
                sortOrder = change.sortOrder ?: topic.sortOrder, active = change.active ?: topic.active,
            ),
        )
    }

    suspend fun seed() = work.catalogWrite {
        // 원본 catalog v1의 코드·문구·정렬·시작 모드를 보존하고 관리자 수정값은 덮어쓰지 않는다.
        val existing = records.topics().map { it.topicCode to it.version }.toSet()
        seeds().forEach { if ((it.topicCode to it.version) !in existing) records.saveTopic(it) }
    }

    private fun seeds(): List<SpeakingTopic> {
        fun seed(code: String, title: String, description: String, level: String, userFirst: Boolean, order: Int) =
            SpeakingTopic(
                topicCode = code, category = code, title = title, description = description,
                recommendedLevel = level,
                recommendedStartMode = if (userFirst) ConversationStartMode.USER_FIRST else ConversationStartMode.AI_FIRST,
                sortOrder = order,
            )
        return listOf(
            seed("DAILY", "Daily Conversation", "Everyday conversation practice", "B1", false, 10),
            seed("TRAVEL", "Travel", "Airport, hotel, transport and sightseeing conversation", "B1", false, 20),
            seed("FOOD", "Food", "Restaurant, cafe, cooking and food ordering conversation", "A2", false, 30),
            seed("SHOPPING", "Shopping", "Price, payment, exchange and delivery conversation", "A2", true, 40),
            seed(
                "BUSINESS", "Business", "Meeting, schedule, customer service and presentation conversation", "B1",
                false, 50,
            ),
            seed("IT", "IT", "Development, API, database, deployment and incident conversation", "B1", true, 60),
            seed("HOBBY", "Hobby", "Movie, music, reading and exercise conversation", "A2", false, 70),
            seed("GAME", "Game", "Cooperation, strategy, character and online game conversation", "A2", false, 80),
            seed(
                "CULTURE", "Culture", "Festival, etiquette, local culture and language difference conversation", "B1",
                false, 90,
            ),
            seed("FREE_TALK", "Free Talk", "Open conversation without a fixed topic", "A2", true, 100),
        )
    }
}
