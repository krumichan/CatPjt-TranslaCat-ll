package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.text.Normalizer

/** 비점수 참고학습의 원문 인용은 모델 문장이 아니라 서버가 부여한 segment ID로 복원한다. */
internal object CuratedWritingFeedbackV3Policy {
    const val version = "curated-writing-feedback-v3"

    data class Segment(val id: String, val text: String)

    fun segments(answer: String): List<Segment> {
        val pieces = answer.split(Regex("(?<=[。！？\n])")).filter(String::isNotEmpty)
        require(pieces.any(String::isNotBlank)) { "WRITING_FEEDBACK_INPUT_INVALID" }
        // 긴 문단도 원문 문자를 버리지 않고 인접 구간만 합쳐 schema의 ID 수를 제한한다.
        val groupSize = (pieces.size + 79) / 80
        return pieces.chunked(groupSize).mapIndexed { index, group -> Segment("S${index + 1}", group.joinToString("")) }
    }

    fun requirements(checklist: JsonArray): List<Pair<String, String>> {
        require(checklist.isNotEmpty() && checklist.size <= 30) { "WRITING_FEEDBACK_INPUT_INVALID" }
        return checklist.mapIndexed { index, value ->
            val text = value.jsonPrimitive.content
            require(text.isNotBlank()) { "WRITING_FEEDBACK_INPUT_INVALID" }
            "C${index + 1}" to text
        }
    }

    fun prompt(
        writingType: WritingType,
        public: JsonObject, reference: JsonObject, answer: String,
        requirements: List<Pair<String, String>>, segments: List<Segment>,
    ): String = buildJsonObject {
        // 과제의 공개 조건과 학습자가 실제로 쓴 내용을 서로 다른 출처로 전달한다.
        put("task", buildJsonObject {
            put("writingType", writingType.name)
            put("publicPromptAndConditions", public)
            put("requirements", JsonArray(requirements.map { (id, text) ->
                buildJsonObject { put("id", id); put("text", text) }
            }))
        })
        put("learnerAnswer", buildJsonObject {
            put("text", answer)
            put("segments", JsonArray(segments.map { segment ->
                buildJsonObject { put("id", segment.id); put("text", segment.text) }
            }))
        })

        // FREE의 사정·내용은 학습자가 선택한다. 참고 예시의 개인 사실을 평가 입력에 넣지 않는다.
        if (writingType != WritingType.FREE) {
            put("referenceExamples", buildJsonObject {
                put("role", "NON_AUTHORITATIVE_EXAMPLES_NOT_LEARNER_FACTS")
                put("answers", reference.getValue("referenceAnswers"))
                put("alternativeNote", reference.getValue("alternativeNote"))
            })
        }
    }.toString()

    val instructions = """
        Give formative writing feedback only; do not assign a score, band, ability profile or weakness tag.
        The task is Korean-to-Japanese. Evaluate each listed requirement exactly once using its ID.
        Treat the learner's own valid choices as valid. A reference answer is an example, not the only correct answer.
        task.publicPromptAndConditions and task.requirements define the task. learnerAnswer is the only source
        of learner facts. referenceExamples, when present, never supplies learner facts or additional requirements.
        For MET evidence and corrections, select only supplied segment IDs. Never quote or invent an answer fragment.
        The server will restore the exact original text for each selected segment ID.
        Classify each observation's evidenceKind. Use MISSING_REQUIREMENT with UNMET and an empty segmentIds array
        when required content is absent, including when the learner explicitly says that a choice is undecided.
        Describe the missing requirement and ask for the learner's own detail. Absence has requirement evidence,
        not a fabricated learner quote. Use LEARNER_TEXT for meaning actually expressed, including a contradictory
        statement or an actual typo; use UNMET for a clear task-meaning contradiction. UNCERTAIN is only for ambiguity.
        Use Korean explanatory prose only in originText and Japanese explanatory prose only in learningText.
        Write a specific explanation of what the learner did or omitted for each requirement. Do not merely
        repeat a checklist question or copy the whole answer into an explanation. Keep answer evidence in segmentIds.
        Japanese learningText must contain no Korean words or particles, including short particles such as 을/를.
        Korean originText must be a Korean explanation, never a copied Japanese answer or checklist question.
        Example lanes: originText="반납 기한을 명확히 썼습니다."; learningText="返却期限を明確に示しています。"
        Necessary corrections require an actual error or an unmet stated requirement. Repair only the selected segment
        and preserve its other valid facts and communicative intent. A missing requirement can be explained without
        manufacturing text that the learner did not write. Never invent a reason, appointment, schedule or other
        learner circumstance to fill a missing requirement; ask the learner to provide their own true detail.
        For FREE tasks, a missing learner choice is UNMET and should be explained as a question or next step in
        observations. Do not fill it with the reference answer's people, places, dates, reasons or materials.
        Omit a necessaryCorrection when a concrete replacement would require choosing facts for the learner.
        A MISSING_REQUIREMENT observation cannot have a necessaryCorrection. Necessary corrections must concern
        an actual selected learner segment and preserve all its valid facts. Do not claim a spelling error unless
        the selected segment actually contains that spelling. Exact quotation confirms provenance, not an error:
        check whether the original form is acceptable before proposing a change.
        Independently review every learner segment for actual morphology, conjugation and orthography errors,
        even when its intended content satisfies a listed requirement. MET describes that requirement, not proof
        that every written form is correct. A genuine form error may have a necessaryCorrection attached to its
        relevant MET LEARNER_TEXT observation. Give a minimal full-segment repair that preserves the valid meaning.
        Never skip an actual form error merely because the intended meaning is understandable, and never invent
        an error in an already acceptable form. Do not add a new grading criterion or change a content status only
        to make room for a correction.
        Optional alternatives are not necessary corrections. Leave the array empty if no well-grounded suggestion
        improves a stated requirement. Do not assert that a valid original is inferior or that another expression is
        more natural or polite without a task-specific reason. Any replacement must be a full grammatical segment.
        Mark optional alternatives as EXPRESSION_ONLY when preserving learner facts, or HYPOTHETICAL_EXAMPLE when
        proposing imagined content. A hypothetical example is only a clearly conditional teaching illustration,
        never an assertion about the learner or evidence of a learner error. Do not use it to replace a missing
        personal reason. Prefer asking the learner to supply their own detail over an unnecessary hypothetical.
        EXPRESSION_ONLY must preserve the exact factual specificity: no new quantity, duration, time range, date,
        place, person or circumstance. Replacing an imprecise amount with an invented precise number is imagined
        content, not an expression-only improvement. Omit unnecessary suggestions rather than supplying new facts.
        A pure spelling-style variant such as the notation of an unchanged number is not inherently more natural,
        polite or suitable for speech. Give no superiority claim without a specific task-based reason. Avoid
        near-duplicate alternatives and prioritize genuine errors over optional rewording.
        Express genuine uncertainty in uncertainties, without converting a task-meaning error into success.
    """.trimIndent()

    fun schema(requirements: List<Pair<String, String>>, segments: List<Segment>): JsonObject {
        fun text(enum: List<String>? = null, description: String? = null) = buildJsonObject {
            put("type", "string")
            if (enum != null) put("enum", JsonArray(enum.map(::JsonPrimitive)))
            if (description != null) put("description", description)
        }
        fun obj(fields: Map<String, JsonElement>) = buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(fields))
            put("required", JsonArray(fields.keys.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }
        fun list(item: JsonElement) = buildJsonObject { put("type", "array"); put("items", item) }
        val ids = requirements.map { it.first }
        val segmentIds = segments.map { it.id }
        val korean = "Explain the learner's evidence or omission in Korean Hangul. Never copy a Japanese answer or checklist question."
        val japanese = "Explain in Japanese only. No Korean words, Hangul particles, or copied full answer."
        val observation = obj(mapOf(
            "requirementId" to text(ids), "status" to text(listOf("MET", "UNMET", "UNCERTAIN")),
            "evidenceKind" to text(listOf("LEARNER_TEXT", "MISSING_REQUIREMENT")),
            "segmentIds" to list(text(segmentIds)), "originText" to text(description = korean),
            "learningText" to text(description = japanese),
        ))
        val correction = obj(mapOf(
            "requirementId" to text(ids), "segmentId" to text(segmentIds), "replacement" to text(),
            "originText" to text(description = korean), "learningText" to text(description = japanese),
        ))
        val optional = obj(mapOf(
            "suggestionKind" to text(listOf("EXPRESSION_ONLY", "HYPOTHETICAL_EXAMPLE")),
            "segmentId" to text(segmentIds), "replacement" to text(),
            "originText" to text(description = korean), "learningText" to text(description = japanese),
        ))
        val uncertainty = obj(mapOf(
            "requirementId" to text(ids), "originText" to text(description = korean),
            "learningText" to text(description = japanese),
        ))
        return obj(mapOf(
            "observations" to list(observation), "necessaryCorrections" to list(correction),
            "optionalAlternatives" to list(optional), "uncertainties" to list(uncertainty),
        ))
    }

    fun validate(
        output: JsonElement, requirements: List<Pair<String, String>>, segments: List<Segment>,
    ): JsonObject {
        try {
            val root = output.jsonObject
            require(root.keys == setOf("observations", "necessaryCorrections", "optionalAlternatives", "uncertainties"))
            val known = segments.associateBy(Segment::id)
            val ids = requirements.map { it.first }.toSet()
            fun fields(value: JsonElement, expected: Set<String>): JsonObject {
                val obj = value.jsonObject
                require(obj.keys == expected)
                return obj
            }
            fun prose(obj: JsonObject) {
                val origin = obj.getValue("originText").jsonPrimitive.content
                val learning = obj.getValue("learningText").jsonPrimitive.content
                require(origin.isNotBlank() && learning.isNotBlank() && origin.length <= 4000 && learning.length <= 4000)
                require(Regex("[가-힣ㄱ-ㅎㅏ-ㅣ]").containsMatchIn(origin))
                require(!Regex("[\\u1100-\\u11ff\\u3130-\\u318f\\ua960-\\ua97f\\uac00-\\ud7ff\\uffa0-\\uffdc]").containsMatchIn(learning))
            }
            val observations = root.getValue("observations").jsonArray
            require(observations.size == ids.size)
            val statuses = mutableMapOf<String, String>()
            val evidenceKinds = mutableMapOf<String, String>()
            val observed = observations.map { raw ->
                val row = fields(raw, setOf("requirementId", "status", "evidenceKind", "segmentIds", "originText", "learningText"))
                val id = row.getValue("requirementId").jsonPrimitive.content
                val status = row.getValue("status").jsonPrimitive.content
                require(id in ids && id !in statuses && status in setOf("MET", "UNMET", "UNCERTAIN"))
                statuses[id] = status
                val evidenceKind = row.getValue("evidenceKind").jsonPrimitive.content
                require(evidenceKind in setOf("LEARNER_TEXT", "MISSING_REQUIREMENT"))
                evidenceKinds[id] = evidenceKind
                val spans = row.getValue("segmentIds").jsonArray.map { it.jsonPrimitive.content }
                require(spans.size <= known.size && spans.distinct().size == spans.size && spans.all { it in known })
                require(status != "MET" || spans.any { known.getValue(it).text.isNotBlank() })
                // 누락의 근거는 과제 요구 자체다. 임의의 기존 문장을 누락된 사실의 인용으로 붙이지 않는다.
                if (evidenceKind == "MISSING_REQUIREMENT") require(status == "UNMET" && spans.isEmpty())
                else require(spans.isNotEmpty())
                prose(row)
                JsonObject(row + ("answerQuotes" to JsonArray(spans.map { JsonPrimitive(known.getValue(it).text) })))
            }
            require(statuses.keys == ids)
            fun rewrite(array: JsonArray, optional: Boolean): JsonArray {
                require(array.size <= 20)
                return JsonArray(array.map { raw ->
                    val expected = setOf("segmentId", "replacement", "originText", "learningText") +
                        if (optional) setOf("suggestionKind") else setOf("requirementId")
                    val row = fields(raw, expected)
                    val segment = known[row.getValue("segmentId").jsonPrimitive.content]
                        ?: error("WRITING_FEEDBACK_SEGMENT_UNKNOWN")
                    if (optional) {
                        require(row.getValue("suggestionKind").jsonPrimitive.content in
                            setOf("EXPRESSION_ONLY", "HYPOTHETICAL_EXAMPLE"))
                    } else {
                        val requirement = row.getValue("requirementId").jsonPrimitive.content
                        require(requirement in ids && evidenceKinds[requirement] == "LEARNER_TEXT")
                        val observation = observed.single { it.getValue("requirementId").jsonPrimitive.content == requirement }
                        require(observation.getValue("segmentIds").jsonArray.any { it.jsonPrimitive.content == segment.id })
                    }
                    val replacement = row.getValue("replacement").jsonPrimitive.content
                    require(replacement.isNotBlank() && replacement.length <= 4000 && replacement != segment.text)
                    require(!Regex("[\\u1100-\\u11ff\\u3130-\\u318f\\ua960-\\ua97f\\uac00-\\ud7ff\\uffa0-\\uffdc]").containsMatchIn(replacement))
                    if (optional && row.getValue("suggestionKind").jsonPrimitive.content == "EXPRESSION_ONLY") {
                        // 표기만 바꾼 같은 수량은 허용하지만, 모델이 새로 정한 수치·날짜는 실제 표현 교체로 저장하지 않는다.
                        require(quantifiedFacts(replacement).all { it in quantifiedFacts(segment.text) })
                    }
                    prose(row)
                    JsonObject(row + ("original" to JsonPrimitive(segment.text)))
                })
            }
            val corrections = rewrite(root.getValue("necessaryCorrections").jsonArray, false)
            val alternatives = rewrite(root.getValue("optionalAlternatives").jsonArray, true)
            val uncertainties = root.getValue("uncertainties").jsonArray
            require(uncertainties.size <= ids.size)
            val checkedUncertainties = uncertainties.map { raw ->
                val row = fields(raw, setOf("requirementId", "originText", "learningText"))
                require(row.getValue("requirementId").jsonPrimitive.content in ids)
                prose(row)
                row
            }
            return buildJsonObject {
                put("policyVersion", version)
                put("resultPolicy", "REFERENCE_ONLY")
                put("observations", JsonArray(observed))
                put("necessaryCorrections", corrections)
                put("optionalAlternatives", alternatives)
                put("uncertainties", JsonArray(checkedUncertainties))
            }
        } catch (_: Exception) {
            throw IllegalArgumentException("WRITING_FEEDBACK_SCHEMA_INVALID")
        }
    }

    /** 기존 수량 파서를 재사용해 표기·단위·구두점만 바꾼 필수 교정을 구별한다. 구형 검증 동작은 바꾸지 않는다. */
    fun quantityNotationComparable(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val canonical = quantifiedFact.replace(normalized) { match ->
            val suffix = normalized.substring(match.range.last + 1)
            val sufficiency = match.value == "十分" &&
                (suffix.startsWith("な") || suffix.startsWith("に") &&
                    !Regex("^に\\s*$number").containsMatchIn(suffix))
            if (sufficiency) match.value else "quantity" + quantifiedFacts(match.value).sorted().joinToString("_")
        }
        return canonical.filter { it.isLetterOrDigit() }
    }

    private fun quantifiedFacts(text: String): Set<String> {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return quantifiedFact.findAll(normalized).flatMap { match ->
            // 「十分に確認する」「十分な準備」の十分は時間ではない。十分に一回などの数え方は除外しない。
            val suffix = normalized.substring(match.range.last + 1)
            val sufficiency = match.value == "十分" &&
                (suffix.startsWith("な") || suffix.startsWith("に") &&
                    !Regex("^に\\s*$number").containsMatchIn(suffix))
            if (sufficiency) return@flatMap emptySequence()

            val counter = match.groupValues[3]
            // 기간 표현의 단위 차이도 같은 양이면 보존한다. 달·연도·날짜는 고정 일수로 환산하지 않는다.
            val seconds = when (counter) {
                "秒", "秒間" -> 1L
                "分", "分間" -> 60L
                "時間" -> 3600L
                "日間" -> 86400L
                "週", "週間" -> 604800L
                else -> null
            }
            listOf(match.groupValues[1], match.groupValues[2]).filter(String::isNotEmpty).map { token ->
                val amount = japaneseNumber(token)
                if (seconds == null) "$counter:${amount.stripTrailingZeros().toPlainString()}"
                else "durationSeconds:${amount.multiply(BigDecimal.valueOf(seconds)).stripTrailingZeros().toPlainString()}"
            }.asSequence()
        }.toSet()
    }

    private fun japaneseNumber(token: String): BigDecimal {
        token.toBigDecimalOrNull()?.let { return it }
        val digits = mapOf('〇' to 0, '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (token.all { it in digits }) return token.map { digits.getValue(it) }.joinToString("").toBigDecimal()

        // 一万二千·三十分 등 일반적인 한자 수사를 숫자로 바꾸며, 수사 밖의 의미는 추측하지 않는다.
        var total = BigDecimal.ZERO
        var section = BigDecimal.ZERO
        var number = BigDecimal.ZERO
        token.forEach { character ->
            if (character in digits) number = BigDecimal.valueOf(digits.getValue(character).toLong())
            else {
                val unit = when (character) {
                    '十' -> 10L; '百' -> 100L; '千' -> 1000L; '万' -> 10000L; '億' -> 100000000L
                    else -> error("WRITING_FEEDBACK_NUMBER_INVALID")
                }
                if (unit < 10000) section += (if (number.signum() == 0) BigDecimal.ONE else number) * BigDecimal.valueOf(unit)
                else {
                    section += number
                    total += (if (section.signum() == 0) BigDecimal.ONE else section) * BigDecimal.valueOf(unit)
                    section = BigDecimal.ZERO
                }
                number = BigDecimal.ZERO
            }
        }
        return total + section + number
    }

    private const val number = "(?:[0-9]+(?:\\.[0-9]+)?|[〇零一二三四五六七八九十百千万億]+)"
    private val quantifiedFact = Regex("($number)(?:\\s*[〜~～–—-]\\s*($number))?\\s*" +
        "(週間|時間|日間|年間|分間|秒間|か月|ヶ月|カ月|箇月|月間|パーセント|キロメートル|メートル|ドル|秒|分|時|日|週|月|年|人|名|回|冊|枚|個|本|歳|才|円|倍|km|m|%)")
}
