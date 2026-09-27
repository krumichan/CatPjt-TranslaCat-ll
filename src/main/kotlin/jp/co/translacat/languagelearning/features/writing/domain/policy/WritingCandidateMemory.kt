package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.JsonObject

/** 같은 슬롯에서 확정 거부된 문항을 노트·분류만 바꿔 재검증하지 않는다. */
internal class WritingCandidateMemory {
    private val seen = mutableSetOf<String>()
    private val rejectedTasks = mutableSetOf<String>()

    fun check(request: JsonObject, draft: WritingCandidateDraft, recoveryBinding: String? = null): String? {
        // 먼저 문항 자체의 확정 거부를 확인하고, 이후 후보별 검증 시도를 기록한다.
        val taskKey = WritingCandidatePolicy.taskKey(draft)
        if (taskKey in rejectedTasks) return "REPEATED_REJECTED_TASK"
        val seenKey = WritingCandidatePolicy.contentHash(request, draft) +
            (recoveryBinding?.let { ":$it" } ?: "")
        if (!seen.add(seenKey)) return "REPEATED_REJECTED_CANDIDATE"
        return null
    }

    fun recordRejection(draft: WritingCandidateDraft, reason: String) {
        if (reason in finalRejections) rejectedTasks += WritingCandidatePolicy.taskKey(draft)
    }

    private companion object {
        val finalRejections = setOf(
            "VERIFIED_BAND_MISMATCH", "QUALITY_TASK_TYPE", "QUALITY_MISSING_FACTS",
            "QUALITY_CONTRADICTORY_GUIDANCE", "QUALITY_AMBIGUOUS_TASK", "QUALITY_BACKGROUND_KNOWLEDGE",
            "QUALITY_UNNATURAL_LANGUAGE", "QUALITY_NOT_LANGUAGE_TASK", "QUALITY_ORIGIN_LANGUAGE",
            "QUALITY_DIVERSITY_SCENE_REPETITION", "QUALITY_KEYWORD_SCOPE_MISMATCH",
        )
    }
}
