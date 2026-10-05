package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingDifficulty

/** 기존 1:3:1 비율을 정수 목표 수에 배분한다. 운영 상한과 문항 분량은 별도이다. */
internal object CuratedWritingPlanPolicy {
    const val policyVersion = "curated-writing-variable-n-v1"
    private val order = listOf(WritingDifficulty.REVIEW, WritingDifficulty.NORMAL, WritingDifficulty.CHALLENGE)
    private val tieOrder = listOf(WritingDifficulty.NORMAL, WritingDifficulty.REVIEW, WritingDifficulty.CHALLENGE)

    fun distribution(target: Int): Map<WritingDifficulty, Int> {
        require(target > 0) { "WRITING_TARGET_INVALID" }
        val weights = mapOf(WritingDifficulty.REVIEW to 1L, WritingDifficulty.NORMAL to 3L,
            WritingDifficulty.CHALLENGE to 1L)
        val result = order.associateWith { (target.toLong() * weights.getValue(it) / 5).toInt() }.toMutableMap()
        val remainders = order.sortedWith(compareByDescending<WritingDifficulty> {
            target.toLong() * weights.getValue(it) % 5
        }.thenBy { tieOrder.indexOf(it) })
        repeat(target - result.values.sum()) { index ->
            val key = remainders[index]
            result[key] = result.getValue(key) + 1
        }
        return result
    }

    fun slots(target: Int, baseBand: Int, preserved: List<CuratedSlot> = emptyList()): List<CuratedSlot> {
        require(baseBand in 1..5 && target >= preserved.size) { "WRITING_TARGET_INVALID" }
        require(preserved.map { it.order }.sorted() == (1..preserved.size).toList())
        val desired = distribution(target)
        val counts = order.associateWith { kind -> preserved.count { it.difficulty == kind } }.toMutableMap()
        val result = preserved.toMutableList()

        // 확대 때 기존 슬롯의 의미·순서를 보존한다. 반올림 편차가 있으면 부족 비중부터 채운다.
        while (result.size < target) {
            val kind = order.maxBy { desired.getValue(it) - counts.getValue(it) }
            val band = when (kind) {
                WritingDifficulty.REVIEW -> (baseBand - 1).coerceAtLeast(1)
                WritingDifficulty.NORMAL -> baseBand
                WritingDifficulty.CHALLENGE -> (baseBand + 1).coerceAtMost(5)
            }
            result += CuratedSlot(result.size + 1, kind, band)
            counts[kind] = counts.getValue(kind) + 1
        }
        return result
    }

    /** 부족해도 최대 확보 집합을 반환한다. 의미 키를 자원으로 매칭해 조합 폭발을 피한다. */
    fun select(slots: List<CuratedSlot>, candidates: List<CuratedWritingItem>, seed: String): CuratedSelection {
        val uniqueIds = candidates.groupBy { it.id }.values.map { versions -> versions.maxBy { it.version } }
        val options = slots.associate { slot -> slot.order to uniqueIds.filter { it.band == slot.targetBand }
            .sortedWith(compareBy<CuratedWritingItem> {
                CuratedWritingManifestCodec.sha256("$seed|${slot.order}|${it.id}|${it.version}")
            }.thenBy { it.id }).distinctBy { it.semanticKey } }
        val occupied = mutableMapOf<String, Int>()
        val assigned = mutableMapOf<Int, CuratedWritingItem>()
        fun augment(slot: Int, seen: MutableSet<String>): Boolean {
            for (candidate in options.getValue(slot)) {
                if (!seen.add(candidate.semanticKey)) continue
                val prior = occupied[candidate.semanticKey]
                if (prior == null || augment(prior, seen)) {
                    occupied[candidate.semanticKey] = slot
                    assigned[slot] = candidate
                    return true
                }
            }
            return false
        }
        slots.sortedWith(compareBy<CuratedSlot> { options.getValue(it.order).size }.thenBy { it.order })
            .forEach { augment(it.order, mutableSetOf()) }
        return CuratedSelection(slots.mapNotNull { slot -> assigned[slot.order]?.let { slot to it } })
    }
}

/** 생성자/명시적 로컬 설정으로 조정하며 100은 제품 구조의 고정 상한이 아니다. */
internal data class CuratedWritingPlanLimits(
    val maxTargetItemCount: Int = 100,
    val workerBatchSize: Int = 20,
    val pageSize: Int = 10,
    val leaseSeconds: Long = 60,
) {
    init {
        require(maxTargetItemCount > 0 && workerBatchSize > 0 && pageSize > 0 && leaseSeconds in 1..3600)
    }
    fun requireTarget(target: Int) {
        require(target in 1..maxTargetItemCount) { "WRITING_TARGET_INVALID" }
    }
}
