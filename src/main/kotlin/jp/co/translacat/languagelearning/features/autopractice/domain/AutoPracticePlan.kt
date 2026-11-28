package jp.co.translacat.languagelearning.features.autopractice.domain

import kotlinx.serialization.Serializable

/** Web AI 문제 구성과 같은 세부 유형을 사용하되 자동 출제에 필요한 정보만 보관한다. */
@Serializable
internal enum class AutoPracticeArea {
    WRITING_TRANSLATION, WRITING_GUIDED, WRITING_FREE,
    READING_COMPREHENSION, READING_STRUCTURE, READING_CONTEXT_INFERENCE,
    SPEAKING_READ_ALOUD, SPEAKING_GUIDED, SPEAKING_FREE,
    LISTENING_DICTATION, LISTENING_COMPREHENSION, LISTENING_SUMMARY;

    val isWriting: Boolean get() = name.startsWith("WRITING_")
}

@Serializable
internal data class AutoPracticeQuantity(val easy: Int = 0, val standard: Int = 0, val hard: Int = 0) {
    val total: Long get() = easy.toLong() + standard + hard
}

/** 수정에는 마지막 조회 revision을 보내 동시 편집이 저장된 플랜을 덮어쓰지 않도록 한다. */
@Serializable
internal data class AutoPracticePlanInput(
    val name: String,
    val studyTime: String,
    val areas: Map<AutoPracticeArea, AutoPracticeQuantity>,
    val enabled: Boolean = false,
    val revision: Long? = null,
)

@Serializable
internal data class AutoPracticePlan(
    val id: String,
    val revision: Long,
    val name: String,
    val studyTime: String,
    val areas: Map<AutoPracticeArea, AutoPracticeQuantity>,
    val originLanguage: String,
    val learningLanguage: String,
    val enabled: Boolean,
    val pauseReason: String? = null,
)

/** 배정일과 실제 생성 시각을 분리하고, 동일 학습일·유형의 작업에 고정 식별자를 부여한다. */
@Serializable
internal data class AutoPracticeJob(
    val id: Long,
    val planId: String,
    val planRevision: Long,
    val originLanguage: String,
    val learningLanguage: String,
    val learningDate: String,
    val area: AutoPracticeArea,
    val quantity: AutoPracticeQuantity,
    val state: String,
    val resourceId: String? = null,
    val reason: String? = null,
    val requestedDate: String? = null,
    val rolloverAt: String? = null,
    val availableAt: String? = null,
)

@Serializable
internal data class AutoPracticeManualOption(val learningDate: String, val rolloverAt: String?)

@Serializable
internal data class AutoPracticeWorkspace(
    val timezone: String,
    val plans: List<AutoPracticePlan>,
    val limits: Map<AutoPracticeArea, Int>,
    val writingLimit: Int,
    val availableAreas: Set<AutoPracticeArea>,
    val jobs: List<AutoPracticeJob>,
    val manual: AutoPracticeManualOption? = null,
)
