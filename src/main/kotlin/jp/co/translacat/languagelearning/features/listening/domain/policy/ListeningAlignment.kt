package jp.co.translacat.languagelearning.features.listening.domain.policy

import kotlinx.serialization.Serializable

@Serializable
internal data class ListeningAlignmentEntry(
    val source: String? = null,
    val answer: String? = null,
    val status: String,
    val sourceIndex: Int? = null,
    val answerIndex: Int? = null,
    val startMs: Int? = null,
    val endMs: Int? = null,
)

internal data class ListeningAlignmentSummary(
    val entries: List<ListeningAlignmentEntry>,
    val referenceCount: Int,
    val answerCount: Int,
) {
    fun count(status: String) = entries.count { it.status == status }
    val recognizedCount get() = entries.count { it.status in setOf("MATCH", "ACCEPTED_VARIANT", "ORDER") }
    val recognitionRatio get() = recognizedCount.toDouble() / maxOf(referenceCount, answerCount, 1)
    val referenceCoverage get() = recognizedCount.toDouble() / maxOf(referenceCount, 1)
}

internal object ListeningAlignment {
    fun align(
        reference: List<String>, answer: List<String>, acceptedTokens: Set<String> = emptySet(),
    ): ListeningAlignmentSummary {
        // 동일 비용에서는 Python 구현과 같이 대각선, 누락, 추가 순서로 선택한다.
        val costs = Array(reference.size + 1) { IntArray(answer.size + 1) }
        val operations = Array(reference.size + 1) { Array(answer.size + 1) { "" } }
        for (row in 1..reference.size) {
            costs[row][0] = row
            operations[row][0] = "OMISSION"
        }
        for (column in 1..answer.size) {
            costs[0][column] = column
            operations[0][column] = "ADDITION"
        }
        for (row in 1..reference.size) {
            for (column in 1..answer.size) {
                val matches = reference[row - 1] == answer[column - 1]
                val candidates = listOf(
                    (costs[row - 1][column - 1] + if (matches) 0 else 1) to if (matches) "MATCH" else "SUBSTITUTION",
                    costs[row - 1][column] + 1 to "OMISSION",
                    costs[row][column - 1] + 1 to "ADDITION",
                )
                val selected = candidates.minBy { it.first }
                costs[row][column] = selected.first
                operations[row][column] = selected.second
            }
        }

        // 역추적으로 원문·답안 위치를 복원한 뒤 순서만 다른 token 집합을 표시한다.
        val entries = mutableListOf<ListeningAlignmentEntry>()
        var row = reference.size
        var column = answer.size
        while (row > 0 || column > 0) {
            when (val operation = operations[row][column]) {
                "MATCH", "SUBSTITUTION" -> {
                    val source = reference[row - 1]
                    val status = if (operation == "MATCH" && source in acceptedTokens) "ACCEPTED_VARIANT" else operation
                    entries += ListeningAlignmentEntry(source, answer[column - 1], status, row - 1, column - 1)
                    row--
                    column--
                }

                "OMISSION" -> {
                    entries += ListeningAlignmentEntry(reference[row - 1], null, operation, row - 1)
                    row--
                }

                else -> {
                    entries += ListeningAlignmentEntry(null, answer[column - 1], "ADDITION", answerIndex = column - 1)
                    column--
                }
            }
        }
        entries.reverse()
        val sameTokens =
            reference != answer && reference.groupingBy { it }.eachCount() == answer.groupingBy { it }.eachCount()
        return ListeningAlignmentSummary(
            entries.map {
                if (sameTokens && it.status !in setOf("MATCH", "ACCEPTED_VARIANT")) it.copy(status = "ORDER") else it
            },
            reference.size, answer.size,
        )
    }

    /** difflib.SequenceMatcher의 기본 autojunk 규칙을 포함한 문자 일치 비율이다. */
    fun sequenceRatio(first: String, second: String): Double {
        val a = first.codePoints().toArray()
        val b = second.codePoints().toArray()
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val positions = mutableMapOf<Int, MutableList<Int>>()
        b.forEachIndexed { index, character -> positions.getOrPut(character) { mutableListOf() }.add(index) }
        if (b.size >= 200) positions.entries.removeIf { it.value.size > b.size / 100 + 1 }

        // 가장 긴 동일 구간을 고르고 남은 좌우 구간을 독립적으로 탐색한다.
        val pending = ArrayDeque<IntArray>()
        pending.add(intArrayOf(0, a.size, 0, b.size))
        var matched = 0
        while (pending.isNotEmpty()) {
            val (aLow, aHigh, bLow, bHigh) = pending.removeLast()
            var bestA = aLow
            var bestB = bLow
            var bestSize = 0
            var prior = mutableMapOf<Int, Int>()
            for (i in aLow until aHigh) {
                val current = mutableMapOf<Int, Int>()
                for (j in positions[a[i]].orEmpty()) {
                    if (j < bLow) continue
                    if (j >= bHigh) break
                    val length = (prior[j - 1] ?: 0) + 1
                    current[j] = length
                    if (length > bestSize) {
                        bestA = i - length + 1
                        bestB = j - length + 1
                        bestSize = length
                    }
                }
                prior = current
            }
            while (bestA > aLow && bestB > bLow && a[bestA - 1] == b[bestB - 1]) {
                bestA--
                bestB--
                bestSize++
            }
            while (bestA + bestSize < aHigh && bestB + bestSize < bHigh && a[bestA + bestSize] == b[bestB + bestSize]) bestSize++
            if (bestSize > 0) {
                matched += bestSize
                if (aLow < bestA && bLow < bestB) pending.add(intArrayOf(aLow, bestA, bLow, bestB))
                if (bestA + bestSize < aHigh && bestB + bestSize < bHigh) pending.add(
                    intArrayOf(bestA + bestSize, aHigh, bestB + bestSize, bHigh),
                )
            }
        }
        return 2.0 * matched / (a.size + b.size)
    }
}
