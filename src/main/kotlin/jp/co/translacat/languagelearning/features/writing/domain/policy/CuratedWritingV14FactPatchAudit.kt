package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*

/** 넓은 original 안의 정상 값은 보존하고, 실제로 충돌한 값만 수리를 요구한다. */
internal object CuratedWritingV14FactPatchAudit {
    private data class Token(val text: String, val start: Int, val end: Int)
    private val relativeWeekdays = Regex("(?:再(?:再|々)*来|翌(?:翌|々)*|先(?:先|々)*|今|来)週(?:の)?[月火水木金土日]曜(?:日)?")

    fun valid(row: JsonObject, plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject): Boolean {
        val segment = plan.segments.single { it.id == row.string("segmentId") }
        val patch = row.getValue("patch").jsonObject
        val original = patch.string("original")
        val replacement = patch.string("replacement")
        val start = segment.text.indexOf(original)
        require(start >= 0 && segment.text.lastIndexOf(original) == start)
        val changed = segment.text.replaceRange(start, start + original.length, replacement)
        val difference = difference(original, replacement)
        val oldStart = start + difference.first
        val oldEnd = start + original.length - difference.second
        val newStart = start + difference.first
        val newEnd = start + replacement.length - difference.second
        val authority = if (row.string("issueType") == "TASK_FACT_CONFLICT") row.string("sourceFactId")
            else row.string("sourceAuthorityId")
        val offsets = sourceOffsets(plan)

        return plan.contract.facts.all { fact ->
            val binding = verified.getValue("facts").jsonObject.getValue(fact.key).jsonObject
            if (binding.string("slotRelation") != "MATCHED_EVENT") return@all true
            val source = plan.sources.single { it.id == binding.string("sourceId") }
            if (source.segmentId != segment.id) return@all true
            val token = binding.string("valueToken")
            val positions = Regex(Regex.escape(token)).findAll(source.text).map {
                Token(token, offsets.getValue(source.id) + it.range.first, offsets.getValue(source.id) + it.range.last + 1)
            }.toList()
            val inPatch = positions.filter { it.start < start + original.length && start < it.end }
            if (inPatch.isEmpty()) return@all true
            if (positions.size != 1) return@all false
            val observed = fact.normalizedAlternatives.getValue(token)
            val touched = inPatch.single().let { it.start < oldEnd && oldStart < it.end }
            if (observed == fact.expectedValue && !preservesNormalDay(original, replacement, fact,
                    difference.first, replacement.length - difference.second)) return@all false

            if (observed != fact.expectedValue) {
                // 다른 슬롯의 오류를 이 행이 권한 없이 고치거나, 틀린 값을 놔둔 채 정답만 덧붙이지 못한다.
                authority == fact.sourceAuthorityId && touched && oldStart < oldEnd && newStart < newEnd &&
                    changedValues(changed, fact, newStart, newEnd) == setOf(fact.expectedValue)
            } else if (!touched) {
                // 정상 값은 바꿀 필요가 없다. 인접 삽입으로 다른 제목·분 단위·주가 된 경우도 보존으로 인정하지 않는다.
                val selected = inPatch.single()
                val mappedStart = if (selected.start >= oldEnd) selected.start + replacement.length - original.length else selected.start
                mappedStart >= 0 && mappedStart + token.length <= changed.length &&
                    changed.substring(mappedStart, mappedStart + token.length) == token &&
                    tokens(changed, fact).any { it.start == mappedStart && it.text == token }
            } else {
                changedValues(changed, fact, newStart, newEnd) == setOf(fact.expectedValue)
            }
        }
    }

    fun repairsExplicitConflict(row: JsonObject, plan: CuratedWritingFeedbackV13Policy.Plan, verified: JsonObject,
                                fact: CuratedWritingMeaningContract.FactConstraint): Boolean {
        val binding = verified.getValue("facts").jsonObject.getValue(fact.key).jsonObject
        val source = plan.sources.single { it.id == binding.string("sourceId") }
        if (row.string("segmentId") != source.segmentId) return false
        val patch = row.getValue("patch").jsonObject
        val segment = plan.segments.single { it.id == source.segmentId }.text
        val start = segment.indexOf(patch.string("original"))
        if (start < 0) return false
        val token = binding.string("valueToken")
        val tokenStart = sourceOffsets(plan).getValue(source.id) + source.text.indexOf(token)
        val original = patch.string("original")
        val replacement = patch.string("replacement")
        val difference = difference(original, replacement)
        val oldStart = start + difference.first
        val oldEnd = start + original.length - difference.second
        val newStart = start + difference.first
        val newEnd = start + replacement.length - difference.second
        val authority = if (row.string("issueType") == "TASK_FACT_CONFLICT") row.string("sourceFactId") else row.string("sourceAuthorityId")
        return authority == fact.sourceAuthorityId && tokenStart < oldEnd && oldStart < tokenStart + token.length &&
            oldStart < oldEnd && newStart < newEnd &&
            changedValues(segment.replaceRange(start, start + original.length, replacement), fact, newStart, newEnd) == setOf(fact.expectedValue)
    }

    private fun changedValues(text: String, fact: CuratedWritingMeaningContract.FactConstraint,
                              start: Int, end: Int): Set<String> = tokens(text, fact).filter {
        it.start < end && start < it.end
    }.map { fact.normalizedAlternatives.getValue(it.text) }.toSet()

    private fun preservesNormalDay(original: String, replacement: String,
                                   fact: CuratedWritingMeaningContract.FactConstraint,
                                   newStart: Int, newEnd: Int): Boolean {
        if (fact.normalizedAlternatives.keys.none(relativeWeekdays::matches)) return true
        fun signature(value: String) = fact.normalizedAlternatives[value] ?: "UNMAPPED:$value"
        val oldCounts = relativeWeekdays.findAll(original).map { signature(it.value) }.groupingBy { it }.eachCount()
        val newGroups = relativeWeekdays.findAll(replacement).groupBy { signature(it.value) }

        // 사전에 없는 새 화요일도 새 요일 표현으로 확인한다. 기존 다른 사건의 표현·정상 별칭은 보존할 수 있다.
        return newGroups.all { (value, matches) ->
            value == fact.expectedValue || matches.size <= oldCounts.getOrDefault(value, 0) ||
                matches.none { it.range.first < newEnd && newStart < it.range.last + 1 }
        }
    }

    private fun tokens(text: String, fact: CuratedWritingMeaningContract.FactConstraint): List<Token> {
        val weeks = relativeWeekdays.findAll(text).map { it.range }.toList()
        val quotedTitles = if (fact.slot == "borrowed_book_title") quotedBookTitles(text) else emptyList()
        val matches = fact.normalizedAlternatives.keys.flatMap { token ->
            Regex(Regex.escape(token)).findAll(text).filter { found ->
                val start = found.range.first
                val end = found.range.last + 1
                val leadingNumber = token.first().isDigit() && start > 0 && text[start - 1].isDigit()
                val trailingNumber = token.last().isDigit() && end < text.length && text[end].isDigit()
                val minuteTail = token.endsWith("時") && Regex("^(半|[0-9０-９]+分)").containsMatchIn(text.substring(end))
                val embeddedWeek = token.contains("週") && weeks.any { it.first < start && end <= it.last + 1 }
                val wrongQuotedTitle = quotedTitles.any { title ->
                    title.range.first < start && end <= title.range.last && title.groupValues[1] != token
                }
                !leadingNumber && !trailingNumber && !minuteTail && !embeddedWeek && !wrongQuotedTitle
            }.map { Token(token, it.range.first, it.range.last + 1) }.toList()
        }
        return matches.filter { match -> matches.none { other ->
            other.start <= match.start && match.end <= other.end && other.end - other.start > match.end - match.start
        } }
    }

    private fun quotedBookTitles(text: String): List<MatchResult> {
        val double = Regex("『([^』]+)』").findAll(text).toList()
        // V13의 제목 구문 경계를 그대로 유지한다. 일반 발화 인용 전체를 책 제목으로 오인하지 않는다.
        val single = Regex("「([^」]+)」").findAll(text).filter { match ->
            val before = text.substring(0, match.range.first)
            val after = text.substring(match.range.last + 1)
            Regex("(?:借りている|借りた|書名(?:は|が)?|タイトル(?:は|が)?|本の(?:名前|題名)(?:は|が)?)\\s*$").containsMatchIn(before) ||
                Regex("^\\s*(?:という|と呼ばれる)?(?:本|書籍)").containsMatchIn(after)
        }.toList()
        return double + single
    }

    private fun difference(original: String, replacement: String): Pair<Int, Int> {
        var prefix = 0
        while (prefix < minOf(original.length, replacement.length) && original[prefix] == replacement[prefix]) prefix++
        while (prefix > 0 && (!boundary(original, prefix) || !boundary(replacement, prefix))) prefix--
        var suffix = 0
        while (suffix < minOf(original.length - prefix, replacement.length - prefix) &&
            original[original.lastIndex - suffix] == replacement[replacement.lastIndex - suffix]) suffix++
        while (suffix > 0 && (!boundary(original, original.length - suffix) || !boundary(replacement, replacement.length - suffix))) suffix--
        return prefix to suffix
    }

    private fun sourceOffsets(plan: CuratedWritingFeedbackV13Policy.Plan): Map<String, Int> {
        val positions = mutableMapOf<String, Int>()
        plan.sources.groupBy { it.segmentId }.forEach { (segment, sources) ->
            var cursor = 0
            sources.forEach { positions[it.id] = cursor; cursor += it.text.length }
            require(sources.joinToString("") { it.text } == plan.segments.single { it.id == segment }.text)
        }
        return positions
    }

    private fun boundary(text: String, index: Int) = index !in 1 until text.length ||
        !(text[index - 1].isHighSurrogate() && text[index].isLowSurrogate())
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
