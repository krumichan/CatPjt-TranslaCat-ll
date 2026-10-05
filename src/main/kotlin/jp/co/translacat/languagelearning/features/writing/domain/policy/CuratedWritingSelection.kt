package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingDifficulty
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import java.security.MessageDigest

/** 검수 상태와 사람 승인을 구분한다. QA 후보는 공개 선택에서 승인으로 취급하지 않는다. */
internal enum class CuratedReviewStatus { DRAFT, AUTO_REVIEWED, REVIEW_REQUIRED, APPROVED, RETIRED }

internal data class CuratedWritingItem(
    val id: String,
    val version: Int,
    val contentHash: String,
    val status: CuratedReviewStatus,
    val releaseId: String,
    val originLanguage: String,
    val learningLanguage: String,
    val writingType: WritingType,
    val band: Int,
    val semanticKey: String,
    val topicKey: String,
    val prompt: String,
    val providedFacts: List<String>,
    val requiredIntents: List<String>,
    val responseConstraints: List<String>,
    val checklist: List<String>,
    val referenceAnswers: List<String>,
    val alternativeNote: String,
    val levelEvidence: String,
    val validAlternative: String,
    val clearError: String,
    val boundaryAnswer: String,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9-]{2,79}")))
        require(version > 0 && contentHash.matches(Regex("[a-f0-9]{64}")))
        require(releaseId.isNotBlank() && originLanguage.isNotBlank() && learningLanguage.isNotBlank())
        require(band in 1..5 && semanticKey.isNotBlank() && topicKey.isNotBlank())
        require(prompt.isNotBlank() && prompt.length <= 2000)
        require(checklist.isNotEmpty() && checklist.none(String::isBlank))
        require(referenceAnswers.size >= 2 && referenceAnswers.none(String::isBlank))
        require(alternativeNote.isNotBlank())
        require(levelEvidence.isNotBlank() && validAlternative.isNotBlank() &&
            clearError.isNotBlank() && boundaryAnswer.isNotBlank())
        if (writingType == WritingType.GUIDED) {
            require(providedFacts.isNotEmpty() && requiredIntents.isNotEmpty() && responseConstraints.isNotEmpty())
        } else {
            require(providedFacts.isEmpty() && requiredIntents.isEmpty() && responseConstraints.isEmpty())
        }
    }
}

internal data class CuratedSlot(
    val order: Int,
    val difficulty: WritingDifficulty,
    val targetBand: Int,
)

internal data class CuratedSelection(val slots: List<Pair<CuratedSlot, CuratedWritingItem>>)

/** 다섯 슬롯의 조합을 함께 풀어 앞 슬롯의 선택 때문에 뒤 슬롯이 비는 일을 막는다. */
internal object CuratedWritingSelection {
    const val policyVersion = "curated-writing-v1"

    fun slots(baseBand: Int): List<CuratedSlot> {
        require(baseBand in 1..5)
        return listOf(
            CuratedSlot(1, WritingDifficulty.REVIEW, (baseBand - 1).coerceAtLeast(1)),
            CuratedSlot(2, WritingDifficulty.NORMAL, baseBand),
            CuratedSlot(3, WritingDifficulty.NORMAL, baseBand),
            CuratedSlot(4, WritingDifficulty.NORMAL, baseBand),
            CuratedSlot(5, WritingDifficulty.CHALLENGE, (baseBand + 1).coerceAtMost(5)),
        )
    }

    fun select(
        candidates: List<CuratedWritingItem>,
        releaseId: String,
        originLanguage: String,
        learningLanguage: String,
        writingType: WritingType,
        baseBand: Int,
        recentIds: Set<String>,
        rePractice: Boolean,
        seed: String,
        qaOnly: Boolean = false,
        requestedSlots: List<CuratedSlot> = slots(baseBand),
        blockedIds: Set<String> = emptySet(),
        blockedSemanticKeys: Set<String> = emptySet(),
    ): CuratedSelection? {
        val available = candidates.filter { item ->
            item.releaseId == releaseId && item.originLanguage == originLanguage &&
                item.learningLanguage == learningLanguage && item.writingType == writingType &&
                (item.status == CuratedReviewStatus.APPROVED ||
                    (qaOnly && item.status in setOf(CuratedReviewStatus.DRAFT,
                        CuratedReviewStatus.AUTO_REVIEWED))) &&
                (rePractice || item.id !in recentIds) && item.id !in blockedIds &&
                item.semanticKey !in blockedSemanticKeys
        }
        val slots = requestedSlots
        require(slots.isNotEmpty() && slots.distinctBy { it.order }.size == slots.size)
        val options = slots.map { slot ->
            available.filter { it.band == slot.targetBand }.sortedWith(
                compareBy<CuratedWritingItem> { stableOrder(seed, slot.order, it.id) }.thenBy { it.id },
            )
        }

        // 가장 제한적인 슬롯부터 탐색하되 결과는 원래 문항 순서로 돌려준다.
        val traversal = slots.indices.sortedWith(compareBy<Int> { options[it].size }.thenBy { it })
        val chosen = arrayOfNulls<CuratedWritingItem>(slots.size)
        val usedIds = mutableSetOf<String>()
        val usedMeanings = mutableSetOf<String>()
        fun solve(depth: Int): Boolean {
            if (depth == traversal.size) return true
            val index = traversal[depth]
            for (item in options[index]) {
                if (item.id in usedIds || item.semanticKey in usedMeanings) continue
                chosen[index] = item
                usedIds += item.id
                usedMeanings += item.semanticKey
                if (solve(depth + 1)) return true
                chosen[index] = null
                usedIds -= item.id
                usedMeanings -= item.semanticKey
            }
            return false
        }
        if (!solve(0)) return null
        return CuratedSelection(slots.indices.map { slots[it] to checkNotNull(chosen[it]) })
    }

    private fun stableOrder(seed: String, slot: Int, id: String): String =
        MessageDigest.getInstance("SHA-256").digest("$seed|$slot|$id".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
